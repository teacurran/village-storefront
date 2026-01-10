package villagecompute.storefront.services.metrics;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized metrics helper for payment processing operations.
 *
 * <p>
 * Provides consistent metric naming and tagging for payment operations including transactions, webhooks, refunds, and
 * Stripe integration. All metrics include tenant_id tags for multi-tenant observability.
 *
 * <p>
 * Metric Types:
 * <ul>
 * <li>Counters: Operation counts (payments, refunds, webhook events)</li>
 * <li>Timers: Operation latency histograms (payment processing, webhook handling)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Observability instrumentation for I5 modules</li>
 * <li>Architecture §3.7: Observability Fabric</li>
 * <li>Architecture §3.12: KPI Tracking (checkout success rate, failure categories)</li>
 * <li>Architecture §3.20.1: Sev1 Alert for Stripe webhook failure rate >5%</li>
 * <li>Foundation §3.0: Rulebook (structured logging + metrics)</li>
 * </ul>
 */
@ApplicationScoped
public class PaymentMetrics {

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // Payment Transaction Metrics
    // ========================================

    /**
     * Record payment transaction initiated.
     *
     * @param tenantId
     *            tenant identifier
     * @param paymentMethod
     *            payment method type (e.g., "card", "gift_card", "store_credit")
     */
    public void recordTransactionInitiated(UUID tenantId, String paymentMethod) {
        meterRegistry.counter("payment.transaction.initiated", "tenant_id", tenantId.toString(), "payment_method",
                paymentMethod).increment();
    }

    /**
     * Record successful payment transaction.
     *
     * @param tenantId
     *            tenant identifier
     * @param paymentMethod
     *            payment method type
     * @param amountCents
     *            transaction amount in cents
     */
    public void recordTransactionSuccess(UUID tenantId, String paymentMethod, long amountCents) {
        meterRegistry.counter("payment.transaction.success", "tenant_id", tenantId.toString(), "payment_method",
                paymentMethod).increment();
        meterRegistry.counter("payment.transaction.amount", "tenant_id", tenantId.toString(), "payment_method",
                paymentMethod).increment(amountCents / 100.0); // convert to dollars
    }

    /**
     * Record failed payment transaction.
     *
     * @param tenantId
     *            tenant identifier
     * @param paymentMethod
     *            payment method type
     * @param failureReason
     *            failure reason (e.g., "insufficient_funds", "card_declined", "authentication_failed")
     */
    public void recordTransactionFailed(UUID tenantId, String paymentMethod, String failureReason) {
        meterRegistry.counter("payment.transaction.failed", "tenant_id", tenantId.toString(), "payment_method",
                paymentMethod, "reason", failureReason).increment();
    }

    /**
     * Record payment transaction duration.
     *
     * <p>
     * Target KPI: p95 latency <300ms per Architecture §3.12 (checkout orchestrator)
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            transaction processing duration in milliseconds
     */
    public void recordTransactionDuration(UUID tenantId, long durationMillis) {
        timer("payment.transaction.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // ========================================
    // Multi-Tender Payment Metrics
    // ========================================

    /**
     * Record multi-tender payment (split payment using multiple methods).
     *
     * @param tenantId
     *            tenant identifier
     * @param tenderCount
     *            number of payment methods used
     */
    public void recordMultiTenderPayment(UUID tenantId, int tenderCount) {
        counter("payment.multi_tender.used", tenantId).increment();
        meterRegistry.counter("payment.multi_tender.count", "tenant_id", tenantId.toString(), "tenders",
                String.valueOf(tenderCount)).increment();
    }

    /**
     * Record gift card redemption as part of payment.
     *
     * @param tenantId
     *            tenant identifier
     * @param amountCents
     *            gift card amount applied in cents
     */
    public void recordGiftCardRedemption(UUID tenantId, long amountCents) {
        counter("payment.gift_card.redeemed", tenantId).increment();
        meterRegistry.counter("payment.gift_card.amount", "tenant_id", tenantId.toString())
                .increment(amountCents / 100.0);
    }

    /**
     * Record store credit redemption as part of payment.
     *
     * @param tenantId
     *            tenant identifier
     * @param amountCents
     *            store credit amount applied in cents
     */
    public void recordStoreCreditRedemption(UUID tenantId, long amountCents) {
        counter("payment.store_credit.redeemed", tenantId).increment();
        meterRegistry.counter("payment.store_credit.amount", "tenant_id", tenantId.toString())
                .increment(amountCents / 100.0);
    }

    // ========================================
    // Refund Metrics
    // ========================================

    /**
     * Record refund initiated.
     *
     * @param tenantId
     *            tenant identifier
     * @param refundType
     *            refund type (e.g., "full", "partial", "store_credit")
     */
    public void recordRefundInitiated(UUID tenantId, String refundType) {
        meterRegistry.counter("payment.refund.initiated", "tenant_id", tenantId.toString(), "refund_type", refundType)
                .increment();
    }

    /**
     * Record successful refund.
     *
     * @param tenantId
     *            tenant identifier
     * @param refundType
     *            refund type
     * @param amountCents
     *            refund amount in cents
     */
    public void recordRefundSuccess(UUID tenantId, String refundType, long amountCents) {
        meterRegistry.counter("payment.refund.success", "tenant_id", tenantId.toString(), "refund_type", refundType)
                .increment();
        meterRegistry.counter("payment.refund.amount", "tenant_id", tenantId.toString(), "refund_type", refundType)
                .increment(amountCents / 100.0);
    }

    /**
     * Record failed refund.
     *
     * @param tenantId
     *            tenant identifier
     * @param refundType
     *            refund type
     * @param failureReason
     *            failure reason
     */
    public void recordRefundFailed(UUID tenantId, String refundType, String failureReason) {
        meterRegistry.counter("payment.refund.failed", "tenant_id", tenantId.toString(), "refund_type", refundType,
                "reason", failureReason).increment();
    }

    /**
     * Record refund processing duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            refund processing duration in milliseconds
     */
    public void recordRefundDuration(UUID tenantId, long durationMillis) {
        timer("payment.refund.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // ========================================
    // Stripe Webhook Metrics
    // ========================================

    /**
     * Record Stripe webhook received.
     *
     * <p>
     * Critical for monitoring webhook failure rate (Sev1 alert at >5% per Architecture §3.20.1)
     *
     * @param tenantId
     *            tenant identifier
     * @param eventType
     *            Stripe event type (e.g., "payment_intent.succeeded", "charge.refunded")
     */
    public void recordWebhookReceived(UUID tenantId, String eventType) {
        meterRegistry.counter("payment.webhook.received", "tenant_id", tenantId.toString(), "event_type", eventType)
                .increment();
    }

    /**
     * Record successful webhook processing.
     *
     * @param tenantId
     *            tenant identifier
     * @param eventType
     *            Stripe event type
     */
    public void recordWebhookProcessed(UUID tenantId, String eventType) {
        meterRegistry.counter("payment.webhook.processed", "tenant_id", tenantId.toString(), "event_type", eventType)
                .increment();
    }

    /**
     * Record failed webhook processing.
     *
     * @param tenantId
     *            tenant identifier
     * @param eventType
     *            Stripe event type
     * @param failureReason
     *            failure reason (e.g., "signature_invalid", "event_not_found", "handler_error")
     */
    public void recordWebhookFailed(UUID tenantId, String eventType, String failureReason) {
        meterRegistry.counter("payment.webhook.failed", "tenant_id", tenantId.toString(), "event_type", eventType,
                "reason", failureReason).increment();
    }

    /**
     * Record webhook processing duration.
     *
     * <p>
     * Includes signature validation, event persistence, and handler dispatch
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            webhook processing duration in milliseconds
     */
    public void recordWebhookDuration(UUID tenantId, long durationMillis) {
        timer("payment.webhook.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record webhook retry attempt.
     *
     * @param tenantId
     *            tenant identifier
     * @param eventType
     *            Stripe event type
     * @param attemptNumber
     *            retry attempt number
     */
    public void recordWebhookRetry(UUID tenantId, String eventType, int attemptNumber) {
        meterRegistry.counter("payment.webhook.retry", "tenant_id", tenantId.toString(), "event_type", eventType,
                "attempt", String.valueOf(attemptNumber)).increment();
    }

    // ========================================
    // Stripe Connect Metrics (for Consignment)
    // ========================================

    /**
     * Record Stripe Connect account onboarding completion.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            consignment vendor identifier
     */
    public void recordConnectOnboardingCompleted(UUID tenantId, UUID vendorId) {
        counter("payment.connect.onboarding.completed", tenantId).increment();
    }

    /**
     * Record Stripe Connect account onboarding failure.
     *
     * @param tenantId
     *            tenant identifier
     * @param failureReason
     *            failure reason
     */
    public void recordConnectOnboardingFailed(UUID tenantId, String failureReason) {
        meterRegistry
                .counter("payment.connect.onboarding.failed", "tenant_id", tenantId.toString(), "reason", failureReason)
                .increment();
    }

    /**
     * Record Stripe Connect payout created.
     *
     * @param tenantId
     *            tenant identifier
     * @param amountCents
     *            payout amount in cents
     */
    public void recordConnectPayoutCreated(UUID tenantId, long amountCents) {
        counter("payment.connect.payout.created", tenantId).increment();
        meterRegistry.counter("payment.connect.payout.amount", "tenant_id", tenantId.toString())
                .increment(amountCents / 100.0);
    }

    /**
     * Record Stripe Connect payout failure.
     *
     * @param tenantId
     *            tenant identifier
     * @param failureReason
     *            failure reason
     */
    public void recordConnectPayoutFailed(UUID tenantId, String failureReason) {
        meterRegistry
                .counter("payment.connect.payout.failed", "tenant_id", tenantId.toString(), "reason", failureReason)
                .increment();
    }

    // ========================================
    // KPI Metrics (Component Performance Indicators)
    // ========================================

    /**
     * Record payment authorization hold.
     *
     * @param tenantId
     *            tenant identifier
     * @param amountCents
     *            authorized amount in cents
     */
    public void recordAuthorizationHold(UUID tenantId, long amountCents) {
        counter("payment.authorization.hold", tenantId).increment();
    }

    /**
     * Record payment capture (fulfillment).
     *
     * @param tenantId
     *            tenant identifier
     * @param amountCents
     *            captured amount in cents
     */
    public void recordAuthorizationCapture(UUID tenantId, long amountCents) {
        counter("payment.authorization.capture", tenantId).increment();
    }

    /**
     * Record payment authorization void.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordAuthorizationVoid(UUID tenantId) {
        counter("payment.authorization.void", tenantId).increment();
    }

    /**
     * Record payment dispute initiated.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordDisputeInitiated(UUID tenantId) {
        counter("payment.dispute.initiated", tenantId).increment();
    }

    /**
     * Record payment dispute resolved.
     *
     * @param tenantId
     *            tenant identifier
     * @param outcome
     *            dispute outcome (e.g., "won", "lost", "accepted")
     */
    public void recordDisputeResolved(UUID tenantId, String outcome) {
        meterRegistry.counter("payment.dispute.resolved", "tenant_id", tenantId.toString(), "outcome", outcome)
                .increment();
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Get or create a counter with tenant_id tag.
     *
     * @param name
     *            metric name
     * @param tenantId
     *            tenant identifier
     * @return counter instance
     */
    private Counter counter(String name, UUID tenantId) {
        return meterRegistry.counter(name, "tenant_id", tenantId.toString());
    }

    /**
     * Get or create a timer with tenant_id tag.
     *
     * @param name
     *            metric name
     * @param tenantId
     *            tenant identifier
     * @return timer instance
     */
    private Timer timer(String name, UUID tenantId) {
        return meterRegistry.timer(name, "tenant_id", tenantId.toString());
    }
}
