package villagecompute.storefront.api.rest;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.featureflags.FeatureFlagged;
import villagecompute.storefront.services.IdempotencyService;
import villagecompute.storefront.services.PaymentService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.util.ProblemDetailsUtil;

/**
 * REST resource for payment intent operations. Provides endpoints for creating, capturing, and refunding payments via
 * Stripe.
 *
 * <p>
 * All endpoints are tenant-scoped and support idempotency via Idempotency-Key header. Payment operations are
 * orchestrated through PaymentService which coordinates provider calls with local persistence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T3: Stripe payment integration with application fees and webhooks</li>
 * <li>OpenAPI: /api/v1/payments endpoints specification</li>
 * <li>RFC7807: Problem Details for HTTP APIs</li>
 * </ul>
 */
@Path("/api/v1/payments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(
        name = "Payments",
        description = "Payment intent creation, capture, and refund operations")
@FeatureFlagged(
        value = "payments.stripe.enabled",
        description = "Kill switch for Stripe payments APIs")
public class PaymentIntentResource {

    private static final Logger LOG = Logger.getLogger(PaymentIntentResource.class);
    private static final String IDEMPOTENCY_OPERATION = "payment-intent.create";

    @Inject
    PaymentService paymentService;

    @Inject
    IdempotencyService idempotencyService;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Create a payment intent for an order.
     *
     * @param request
     *            payment intent creation request
     * @return payment intent details including client secret for Stripe.js
     */
    @POST
    @Path("/intents")
    @Operation(
            operationId = "createPaymentIntent",
            summary = "Create payment intent",
            description = """
                    Creates a Stripe payment intent with platform fee calculation and metadata.
                    Returns client secret for use with Stripe.js or mobile SDKs.

                    **Idempotency:** Supply Idempotency-Key header to safely retry requests.
                    **Feature Flags:** Respects `payments.stripe.enabled` kill switch.
                    **Authentication:** Requires valid session or guest checkout token.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "201",
                    description = "Payment intent created successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = PaymentIntentResponse.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Invalid request parameters",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "409",
                            description = "Idempotency key conflict - different request body with same key",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Payment provider error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response createPaymentIntent(@Parameter(
            description = "Idempotency key ensuring safe retries",
            required = false) @HeaderParam("Idempotency-Key") String idempotencyKeyHeader,
            @Valid CreatePaymentIntentRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String idempotencyKey = resolveIdempotencyKey(idempotencyKeyHeader, request.idempotencyKey());

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            Map<String, Object> body = ProblemDetailsUtil.badRequest("Idempotency key is required");
            return Response.status(Response.Status.BAD_REQUEST).entity(body).build();
        }

        try {
            LOG.infof("[Tenant: %s] Creating payment intent: amount=%s %s, order=%s", tenantId, request.amount,
                    request.currency, request.orderId);

            IdempotencyKey keyRecord = idempotencyService.acquire(idempotencyKey, IDEMPOTENCY_OPERATION, 60);
            if (keyRecord.isSuccess() && keyRecord.result != null) {
                PaymentIntentResponse cached = deserializeResponse(keyRecord.result);
                if (cached != null) {
                    int status = keyRecord.responseCode != null ? keyRecord.responseCode
                            : Response.Status.CREATED.getStatusCode();
                    return Response.status(status).entity(cached).build();
                }
            }

            PaymentIntent paymentIntent = paymentService.createPaymentIntent(request.amount, request.currency,
                    request.orderId, request.captureImmediately, idempotencyKey);

            PaymentIntentResponse response = toResponse(paymentIntent, true);

            idempotencyService.markSuccess(idempotencyKey, serializeResponse(response),
                    Response.Status.CREATED.getStatusCode());
            return Response.status(Response.Status.CREATED).entity(response).build();

        } catch (IdempotencyConflictException e) {
            LOG.warnf("[Tenant: %s] Idempotency conflict: %s", tenantId, e.getMessage());
            return Response.status(Response.Status.CONFLICT).entity(ProblemDetailsUtil.conflict(e.getMessage()))
                    .build();
        } catch (IllegalArgumentException e) {
            LOG.warnf("[Tenant: %s] Invalid payment request: %s", tenantId, e.getMessage());
            Map<String, Object> body = ProblemDetailsUtil.badRequest(e.getMessage());
            idempotencyService.markFailed(idempotencyKey, serializeResponse(body),
                    Response.Status.BAD_REQUEST.getStatusCode());
            return Response.status(Response.Status.BAD_REQUEST).entity(body).build();
        } catch (Exception e) {
            LOG.errorf(e, "[Tenant: %s] Failed to create payment intent", tenantId);
            Map<String, Object> body = ProblemDetailsUtil
                    .internalServerError("Payment provider error: " + e.getMessage());
            idempotencyService.markFailed(idempotencyKey, serializeResponse(body),
                    Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
        }
    }

    /**
     * Capture a previously authorized payment.
     *
     * @param paymentIntentId
     *            local payment intent ID
     * @param request
     *            capture request with optional amount
     * @return updated payment intent details
     */
    @POST
    @Path("/intents/{id}/capture")
    @Operation(
            operationId = "capturePayment",
            summary = "Capture authorized payment",
            description = """
                    Captures a payment intent that was created with manual capture mode.
                    Used for delayed capture flows (e.g., after fulfillment).

                    **Authorization:** Requires admin or order owner permissions.
                    **Feature Flags:** Respects `payments.stripe.enabled` kill switch.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Payment captured successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = PaymentIntentResponse.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Payment not in authorized state",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Payment intent not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Payment provider error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response capturePayment(@Parameter(
            description = "Payment intent ID",
            required = true) @PathParam("id") Long paymentIntentId, @Valid CapturePaymentRequest request) {

        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            LOG.infof("[Tenant: %s] Capturing payment: id=%d, amount=%s", tenantId, paymentIntentId,
                    request != null && request.amountToCapture != null ? request.amountToCapture : "full");

            BigDecimal amountToCapture = request != null ? request.amountToCapture : null;
            PaymentIntent paymentIntent = paymentService.capturePayment(paymentIntentId, amountToCapture);

            PaymentIntentResponse response = toResponse(paymentIntent, false);

            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("[Tenant: %s] Payment not found: %d", tenantId, paymentIntentId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("Payment intent not found")).build();
        } catch (IllegalStateException e) {
            LOG.warnf("[Tenant: %s] Invalid payment state for capture: %s", tenantId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "[Tenant: %s] Failed to capture payment: %d", tenantId, paymentIntentId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Payment provider error: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Refund a captured payment.
     *
     * @param paymentIntentId
     *            local payment intent ID
     * @param request
     *            refund request with amount and reason
     * @return updated payment intent details
     */
    @POST
    @Path("/intents/{id}/refund")
    @Operation(
            operationId = "refundPayment",
            summary = "Refund captured payment",
            description = """
                    Refunds a captured payment, either fully or partially.
                    Automatically handles consignment commission reversals.

                    **Authorization:** Requires admin permissions.
                    **Feature Flags:** Respects `payments.stripe.enabled` kill switch.
                    **Side Effects:** Triggers order refund workflow and consignment adjustments.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Refund processed successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = PaymentIntentResponse.class))),
                    @APIResponse(
                            responseCode = "400",
                            description = "Payment not in captured state or invalid amount",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "404",
                            description = "Payment intent not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON)),
                    @APIResponse(
                            responseCode = "500",
                            description = "Payment provider error",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response refundPayment(@Parameter(
            description = "Payment intent ID",
            required = true) @PathParam("id") Long paymentIntentId, @Valid @NotNull RefundPaymentRequest request) {

        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            LOG.infof("[Tenant: %s] Refunding payment: id=%d, amount=%s, reason=%s", tenantId, paymentIntentId,
                    request.amountToRefund, request.reason);

            PaymentIntent paymentIntent = paymentService.refundPayment(paymentIntentId, request.amountToRefund,
                    request.reason);

            PaymentIntentResponse response = toResponse(paymentIntent, false);

            return Response.ok(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("[Tenant: %s] Payment not found: %d", tenantId, paymentIntentId);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ProblemDetailsUtil.notFound("Payment intent not found")).build();
        } catch (IllegalStateException e) {
            LOG.warnf("[Tenant: %s] Invalid payment state for refund: %s", tenantId, e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST).entity(ProblemDetailsUtil.badRequest(e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.errorf(e, "[Tenant: %s] Failed to refund payment: %d", tenantId, paymentIntentId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Payment provider error: " + e.getMessage()))
                    .build();
        }
    }

    /**
     * Get payment intent details.
     *
     * @param paymentIntentId
     *            local payment intent ID
     * @return payment intent details
     */
    @GET
    @Path("/intents/{id}")
    @Operation(
            operationId = "getPaymentIntent",
            summary = "Get payment intent details",
            description = """
                    Retrieves payment intent details by local ID.

                    **Authorization:** Requires admin or order owner permissions.
                    """)
    @APIResponses(
            value = {@APIResponse(
                    responseCode = "200",
                    description = "Payment intent found",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(
                                    implementation = PaymentIntentResponse.class))),
                    @APIResponse(
                            responseCode = "404",
                            description = "Payment intent not found",
                            content = @Content(
                                    mediaType = MediaType.APPLICATION_JSON))})
    public Response getPaymentIntent(@Parameter(
            description = "Payment intent ID",
            required = true) @PathParam("id") Long paymentIntentId) {

        UUID tenantId = TenantContext.getCurrentTenantId();

        try {
            PaymentIntent paymentIntent = PaymentIntent.findByIdAndTenant(tenantId, paymentIntentId);

            if (paymentIntent == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ProblemDetailsUtil.notFound("Payment intent not found")).build();
            }

            PaymentIntentResponse response = toResponse(paymentIntent, false);

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.errorf(e, "[Tenant: %s] Failed to retrieve payment intent: %d", tenantId, paymentIntentId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ProblemDetailsUtil.internalServerError("Failed to retrieve payment intent")).build();
        }
    }

    /**
     * Request DTOs
     */

    public static record CreatePaymentIntentRequest(@NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String currency, UUID orderId, boolean captureImmediately, String idempotencyKey) {
    }

    public static record CapturePaymentRequest(BigDecimal amountToCapture) {
    }

    public static record RefundPaymentRequest(@NotNull @DecimalMin("0.01") BigDecimal amountToRefund, String reason) {
    }

    /**
     * Response DTOs
     */

    public static record PaymentIntentResponse(Long id, String providerPaymentId, String clientSecret, String status,
            BigDecimal amount, String currency, UUID orderId, Map<String, Object> metadata) {
    }

    private PaymentIntentResponse toResponse(PaymentIntent paymentIntent, boolean includeClientSecret) {
        return new PaymentIntentResponse(paymentIntent.id, paymentIntent.providerPaymentId,
                includeClientSecret ? paymentIntent.clientSecret : null, paymentIntent.status.name(),
                paymentIntent.amountCaptured != null && !includeClientSecret ? paymentIntent.amountCaptured
                        : paymentIntent.amount,
                paymentIntent.currency, paymentIntent.orderId, parseMetadata(paymentIntent.metadata));
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse payment metadata JSON");
            return Collections.emptyMap();
        }
    }

    private String serializeResponse(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            LOG.debug("Failed to serialize idempotency payload", e);
            return "{}";
        }
    }

    private PaymentIntentResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, PaymentIntentResponse.class);
        } catch (Exception e) {
            LOG.warn("Failed to deserialize cached payment intent response", e);
            return null;
        }
    }

    private String resolveIdempotencyKey(String headerValue, String payloadValue) {
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        return payloadValue;
    }
}
