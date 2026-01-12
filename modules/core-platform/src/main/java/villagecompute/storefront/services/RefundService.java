package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Refund;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.services.returns.ReturnWorkflowCoordinator;
import villagecompute.storefront.services.returns.ReturnWorkflowItem;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Refund orchestration service. Coordinates refund requests with payment provider, consignment reversals, and audit
 * logging.
 *
 * <p>
 * Provides idempotent refund operations with multi-tenant safety, validation, and domain event emission. Integrates
 * with PaymentService, ConsignmentService, and DomainEventPublisher.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T9: Refund and return implementation</li>
 * <li>ADR-003: Checkout saga and compensating transactions</li>
 * <li>Architecture: Payment Integration Layer</li>
 * </ul>
 */
@ApplicationScoped
public class RefundService {

    private static final Logger LOG = Logger.getLogger(RefundService.class);
    private static final String OPERATION_TYPE = "refund";
    private static final int IDEMPOTENCY_TTL_MINUTES = 60;

    @Inject
    PaymentService paymentService;

    @Inject
    IdempotencyService idempotencyService;

    @Inject
    DomainEventPublisher eventPublisher;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    AuditLogService auditLogService;

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    ReturnWorkflowCoordinator returnWorkflowCoordinator;

    /**
     * Initiate a refund for an order with idempotency guarantee.
     *
     * @param orderId
     *            Order UUID
     * @param amountToRefund
     *            Amount to refund (null = full refund)
     * @param reason
     *            Refund reason
     * @param idempotencyKey
     *            Client-provided idempotency key
     * @param restockItems
     *            Whether to restock inventory
     * @return Refund result
     */
    @Transactional
    public RefundResult initiateRefund(UUID orderId, BigDecimal amountToRefund, String reason, String idempotencyKey,
            boolean restockItems) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Initiating refund - tenantId=%s, orderId=%s, amount=%s, reason=%s, idempotencyKey=%s", tenantId,
                orderId, amountToRefund, reason, idempotencyKey);

        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            IdempotencyKey key = idempotencyService.acquire(idempotencyKey, OPERATION_TYPE, IDEMPOTENCY_TTL_MINUTES);

            if (key.isSuccess()) {
                LOG.infof("Returning cached refund result - tenantId=%s, orderId=%s, idempotencyKey=%s", tenantId,
                        orderId, idempotencyKey);
                meterRegistry.counter("refund.service.idempotency.hit", "tenant", tenantId.toString()).increment();
                return deserializeRefundResult(key.result).asCachedResult();
            }

            if (key.isFailed()) {
                LOG.warnf("Returning cached refund error - tenantId=%s, orderId=%s, idempotencyKey=%s", tenantId,
                        orderId, idempotencyKey);
                throw new IllegalStateException("Previous refund attempt failed: " + key.error);
            }

            Order order = Order.findById(orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                String error = "Order not found: " + orderId;
                markIdempotencyFailed(idempotencyKey, error, 404);
                throw new EntityNotFoundException(error);
            }

            if (order.paymentIntentId == null) {
                String error = "Order has no payment intent: " + orderId;
                markIdempotencyFailed(idempotencyKey, error, 400);
                throw new IllegalStateException(error);
            }

            PaymentIntent paymentIntent = PaymentIntent.findByProviderPaymentId(tenantId, order.paymentIntentId);
            if (paymentIntent == null) {
                String error = "Payment intent not found: " + order.paymentIntentId;
                markIdempotencyFailed(idempotencyKey, error, 404);
                throw new EntityNotFoundException(error);
            }

            if (paymentIntent.status != PaymentIntent.PaymentStatus.CAPTURED) {
                String error = "Payment not captured, cannot refund: " + paymentIntent.status;
                markIdempotencyFailed(idempotencyKey, error, 400);
                throw new IllegalStateException(error);
            }

            BigDecimal amountCaptured = paymentIntent.amountCaptured != null ? paymentIntent.amountCaptured
                    : BigDecimal.ZERO;
            BigDecimal alreadyRefunded = paymentIntent.amountRefunded != null ? paymentIntent.amountRefunded
                    : BigDecimal.ZERO;
            BigDecimal maxRefundable = amountCaptured.subtract(alreadyRefunded);

            BigDecimal finalRefundAmount = amountToRefund != null ? amountToRefund : maxRefundable;

            if (finalRefundAmount.compareTo(BigDecimal.ZERO) <= 0) {
                String error = "Refund amount must be positive: " + finalRefundAmount;
                markIdempotencyFailed(idempotencyKey, error, 400);
                throw new IllegalArgumentException(error);
            }

            if (finalRefundAmount.compareTo(maxRefundable) > 0) {
                String error = "Refund amount exceeds refundable amount: " + finalRefundAmount + " > " + maxRefundable;
                markIdempotencyFailed(idempotencyKey, error, 400);
                throw new IllegalArgumentException(error);
            }

            String normalizedReason = sanitizeReason(reason);
            emitRefundEvent(tenantId, orderId, "REFUND_INITIATED", finalRefundAmount, normalizedReason);

            PaymentIntent updatedIntent = paymentService.refundPayment(paymentIntent.id, finalRefundAmount, reason);
            Refund refund = Refund.findLatestByPaymentIntent(tenantId, paymentIntent.id);
            if (refund == null) {
                throw new IllegalStateException("Refund record not found after processing");
            }

            refund.idempotencyKey = idempotencyKey;
            refund.persist();

            if (updatedIntent.amountRefunded.compareTo(amountCaptured) >= 0) {
                order.status = Order.OrderStatus.REFUNDED;
                order.persist();
                LOG.infof("Order marked as REFUNDED - tenantId=%s, orderId=%s", tenantId, orderId);
            }

            if (restockItems) {
                enqueueRestockTasks(order, normalizedReason);
            }

            loyaltyService.reversePointsForOrderRefund(order.user != null ? order.user.id : null, order.id,
                    order.totalAmount, finalRefundAmount, normalizedReason);

            emitRefundEvent(tenantId, orderId, "REFUND_COMPLETED", finalRefundAmount, normalizedReason);

            auditLogService.recordOrderAction("order_refund_initiated", order.id,
                    order.user != null ? order.user.id : null,
                    Map.of("amount", finalRefundAmount.toPlainString(), "currency", paymentIntent.currency, "reason",
                            normalizedReason, "providerRefundId", refund.providerRefundId));

            BigDecimal remainingRefundable = amountCaptured.subtract(updatedIntent.amountRefunded);
            RefundResult result = new RefundResult(refund.id, buildReferenceId(order.tenant.id, refund.id),
                    refund.providerRefundId, finalRefundAmount, paymentIntent.currency,
                    refund.status != null ? refund.status.name().toLowerCase(Locale.ROOT) : "pending",
                    convertInstant(refund.createdAt), convertInstant(refund.updatedAt),
                    remainingRefundable.max(BigDecimal.ZERO), paymentIntent.providerPaymentId, normalizedReason, false);

            String resultJson = serializeRefundResult(result);
            idempotencyService.markSuccess(idempotencyKey, resultJson, 201);

            meterRegistry.counter("refund.service.refund.completed", "tenant", tenantId.toString()).increment();

            LOG.infof("Refund completed successfully - tenantId=%s, orderId=%s, refundId=%d, amount=%s", tenantId,
                    orderId, refund.id, finalRefundAmount);

            return result;

        } catch (IdempotencyConflictException e) {
            LOG.warnf("Idempotency conflict - tenantId=%s, orderId=%s, idempotencyKey=%s", tenantId, orderId,
                    idempotencyKey);
            meterRegistry.counter("refund.service.idempotency.conflict", "tenant", tenantId.toString()).increment();
            throw e;

        } catch (IllegalArgumentException | IllegalStateException | EntityNotFoundException e) {
            LOG.warnf(e, "Refund validation failed - tenantId=%s, orderId=%s", tenantId, orderId);
            meterRegistry.counter("refund.service.refund.validation_failed", "tenant", tenantId.toString()).increment();
            throw e;

        } catch (Exception e) {
            LOG.errorf(e, "Refund processing failed - tenantId=%s, orderId=%s", tenantId, orderId);
            markIdempotencyFailed(idempotencyKey, e.getMessage(), 500);
            meterRegistry.counter("refund.service.refund.failed", "tenant", tenantId.toString()).increment();
            throw new RuntimeException("Refund processing failed: " + e.getMessage(), e);

        } finally {
            sample.stop(meterRegistry.timer("refund.service.refund.duration", "tenant", tenantId.toString()));
        }
    }

    private void enqueueRestockTasks(Order order, String reason) {
        try {
            List<OrderLineItem> lineItems = OrderLineItem
                    .find("order.id = ?1 and tenant.id = ?2", order.id, order.tenant.id).list();
            if (lineItems == null || lineItems.isEmpty()) {
                return;
            }
            List<ReturnWorkflowItem> workflowItems = lineItems.stream().map(item -> new ReturnWorkflowItem(item.id,
                    item.quantity != null ? item.quantity : 0, reason, "auto_restock_refund"))
                    .filter(item -> item.quantity() > 0).toList();
            if (workflowItems.isEmpty()) {
                return;
            }
            returnWorkflowCoordinator.enqueueTasks(order, lineItems, workflowItems, null, "refund_restock");
            LOG.infof("Queued restock tasks for refund - tenantId=%s, orderId=%s, count=%d", order.tenant.id, order.id,
                    workflowItems.size());
        } catch (Exception e) {
            LOG.warnf(e, "Failed to queue restock tasks for refund - orderId=%s", order.id);
        }
    }

    private OffsetDateTime convertInstant(java.time.Instant instant) {
        return instant != null ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC) : OffsetDateTime.now(ZoneOffset.UTC);
    }

    private UUID buildReferenceId(UUID tenantId, Long refundId) {
        String source = tenantId.toString() + ":" + refundId;
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeReason(String reason) {
        return (reason == null || reason.isBlank()) ? "requested_by_customer" : reason;
    }

    private void markIdempotencyFailed(String idempotencyKey, String error, int responseCode) {
        try {
            Map<String, Object> errorPayload = new HashMap<>();
            errorPayload.put("error", error);
            errorPayload.put("responseCode", responseCode);
            idempotencyService.markFailed(idempotencyKey, objectMapper.writeValueAsString(errorPayload), responseCode);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to mark idempotency key as failed - key=%s", idempotencyKey);
        }
    }

    private void emitRefundEvent(UUID tenantId, UUID orderId, String eventType, BigDecimal amount, String reason) {
        try {
            Map<String, Object> eventPayload = new HashMap<>();
            eventPayload.put("orderId", orderId.toString());
            eventPayload.put("amount", amount.toPlainString());
            eventPayload.put("reason", reason);
            eventPayload.put("userId", "system"); // TODO: Add user context when auth is implemented

            eventPublisher.publish("ORDER", orderId, eventType, objectMapper.writeValueAsString(eventPayload));

            LOG.infof("Emitted event - tenantId=%s, eventType=%s, orderId=%s", tenantId, eventType, orderId);

        } catch (Exception e) {
            LOG.warnf(e, "Failed to emit refund event - tenantId=%s, eventType=%s, orderId=%s", tenantId, eventType,
                    orderId);
        }
    }

    private String serializeRefundResult(RefundResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to serialize refund result - refundId=%d", result.refundId());
            return "{}";
        }
    }

    private RefundResult deserializeRefundResult(String json) {
        try {
            return objectMapper.readValue(json, RefundResult.class);
        } catch (JsonProcessingException e) {
            LOG.warnf(e, "Failed to deserialize refund result from cache");
            throw new IllegalStateException("Failed to deserialize cached refund result", e);
        }
    }

    /**
     * Refund result DTO.
     *
     * @param refundId
     *            Local refund ID
     * @param providerRefundId
     *            Provider refund ID
     * @param amount
     *            Refunded amount
     * @param currency
     *            Currency code
     * @param status
     *            Refund status
     * @param createdAt
     *            Creation timestamp
     * @param remainingRefundable
     *            Remaining refundable amount
     */
    public record RefundResult(Long refundId, UUID referenceId, String providerRefundId, BigDecimal amount,
            String currency, String status, OffsetDateTime createdAt, OffsetDateTime processedAt,
            BigDecimal remainingRefundable, String paymentIntentId, String reason, boolean fromCache) {

        public RefundResult asCachedResult() {
            if (fromCache) {
                return this;
            }
            return new RefundResult(refundId, referenceId, providerRefundId, amount, currency, status, createdAt,
                    processedAt, remainingRefundable, paymentIntentId, reason, true);
        }
    }
}
