package villagecompute.storefront.api.rest;

import java.io.IOException;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.payment.WebhookHandler;
import villagecompute.storefront.payment.stripe.StripeWebhookHandler;

/**
 * REST endpoint for receiving payment provider webhooks. Handles asynchronous notifications from Stripe and other
 * payment providers.
 *
 * Path: /api/webhooks/payments/{provider}
 */
@Path("/api/webhooks/payments")
@Produces(MediaType.APPLICATION_JSON)
public class PaymentWebhookResource {

    private static final Logger LOGGER = Logger.getLogger(PaymentWebhookResource.class);

    @Inject
    StripeWebhookHandler stripeWebhookHandler;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Handle Stripe webhook events. Stripe-Signature header is used for signature verification.
     *
     * @param payload
     *            Raw webhook payload (JSON string)
     * @param stripeSignature
     *            Stripe signature header
     * @return 200 OK if processed, 400 if invalid signature, 500 if processing failed
     */
    @POST
    @Path("/stripe")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response handleStripeWebhook(String payload, @HeaderParam("Stripe-Signature") String stripeSignature,
            @HeaderParam("Stripe-Event-Id") String eventId) {

        LOGGER.debugf("Received Stripe webhook: eventId=%s, signature=%s", eventId,
                stripeSignature != null ? "present" : "missing");

        if (stripeSignature == null || stripeSignature.isEmpty()) {
            LOGGER.warn("Stripe webhook missing signature header");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Missing Stripe-Signature header")).build();
        }

        try {
            WebhookPayloadInfo payloadInfo = parseWebhookPayload(payload);
            if (payloadInfo == null) {
                return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Invalid webhook payload"))
                        .build();
            }

            String providerEventId = eventId != null ? eventId : payloadInfo.eventId();
            String eventType = payloadInfo.eventType();

            WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest(providerEventId, eventType,
                    payload, stripeSignature, Map.of("Stripe-Signature", stripeSignature));

            WebhookHandler.WebhookProcessingResult result = stripeWebhookHandler.processWebhook(request);

            if (!result.success() && !result.alreadyProcessed()) {
                LOGGER.errorf("Failed to process Stripe webhook: %s, error: %s", providerEventId, result.error());
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(Map.of("error", result.error()))
                        .build();
            }

            if (result.alreadyProcessed()) {
                LOGGER.debugf("Stripe webhook already processed: %s", providerEventId);
            }

            return Response.ok(Map.of("received", true, "eventId", result.eventId(), "alreadyProcessed",
                    result.alreadyProcessed())).build();

        } catch (Exception e) {
            LOGGER.errorf(e, "Unexpected error processing Stripe webhook");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal server error")).build();
        }
    }

    /**
     * Health check endpoint for webhook configuration.
     */
    @GET
    @Path("/health")
    public Response healthCheck() {
        return Response.ok(Map.of("status", "healthy", "providers", new String[]{"stripe"})).build();
    }

    private WebhookPayloadInfo parseWebhookPayload(String payload) throws IOException {
        JsonNode node = objectMapper.readTree(payload);
        String id = node.path("id").asText(null);
        String type = node.path("type").asText(null);
        if (id == null || type == null) {
            return null;
        }
        return new WebhookPayloadInfo(id, type);
    }

    private record WebhookPayloadInfo(String eventId, String eventType) {
    }
}
