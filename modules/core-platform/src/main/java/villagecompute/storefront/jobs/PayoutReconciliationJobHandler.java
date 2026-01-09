package villagecompute.storefront.jobs;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.Payout;

import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.payment.stripe.StripeConfig;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Background job handler for reconciling consignment payouts with Stripe Connect payouts.
 *
 * Verifies payout amounts match expected consignment totals and updates local payout status. Flags discrepancies for
 * manual review.
 */
@ApplicationScoped
public class PayoutReconciliationJobHandler {

    private static final Logger LOGGER = Logger.getLogger(PayoutReconciliationJobHandler.class);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    @Inject
    StripeConfig stripeConfig;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Process payout reconciliation job.
     *
     * @param jobPayload
     *            JSON payload containing reconciliation parameters
     * @throws Exception
     *             if reconciliation fails
     */
    @Transactional
    public void handle(PayoutReconciliationJobPayload payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);
        UUID tenantId = TenantContext.getCurrentTenantId();
        String tenantTag = tenantId != null ? tenantId.toString() : "unknown";

        try {
            UUID payoutBatchId = payload.payoutBatchId();
            String providerPayoutId = payload.providerPayoutId();
            BigDecimal expectedAmount = payload.expectedAmount() != null ? payload.expectedAmount() : BigDecimal.ZERO;
            String currency = payload.currency() != null ? payload.currency() : "USD";

            LOGGER.infof(
                    "[Tenant: %s] Starting payout reconciliation job %s: batchId=%s, providerPayoutId=%s, expectedAmount=%s %s",
                    tenantTag, payload.jobId(), payoutBatchId, providerPayoutId, expectedAmount, currency);

            meterRegistry.counter("payments.payout.reconciliation.started", "tenant", tenantTag).increment();

            PayoutBatch payoutBatch = PayoutBatch.findById(payoutBatchId);
            if (payoutBatch == null) {
                String error = String.format("PayoutBatch not found: %s", payoutBatchId);
                LOGGER.errorf("[Tenant: %s] %s", tenantTag, error);
                meterRegistry.counter("payments.payout.reconciliation.failed", "tenant", tenantTag, "reason",
                        "batch_not_found").increment();
                throw new IllegalArgumentException(error);
            }

            if (!payoutBatch.tenant.id.equals(tenantId)) {
                String error = String.format("PayoutBatch tenant mismatch: expected %s, got %s", tenantId,
                        payoutBatch.tenant.id);
                LOGGER.errorf("[Tenant: %s] %s", tenantTag, error);
                throw new SecurityException(error);
            }

            Stripe.apiKey = stripeConfig.apiSecretKey();
            Payout payout;
            try {
                payout = Payout.retrieve(providerPayoutId);
            } catch (StripeException e) {
                LOGGER.errorf(e, "[Tenant: %s] Failed to retrieve Stripe payout %s", tenantTag, providerPayoutId);
                meterRegistry.counter("payments.payout.reconciliation.failed", "tenant", tenantTag, "reason",
                        "stripe_api_error").increment();
                throw new RuntimeException("Failed to retrieve payout from Stripe: " + e.getMessage(), e);
            }

            BigDecimal actualAmount = BigDecimal.valueOf(payout.getAmount()).movePointLeft(2);
            String payoutStatus = payout.getStatus();

            LOGGER.infof(
                    "[Tenant: %s] Retrieved Stripe payout: id=%s, status=%s, amount=%s, expectedAmount=%s, currency=%s",
                    tenantTag, providerPayoutId, payoutStatus, actualAmount, expectedAmount, currency);

            BigDecimal amountDifference = expectedAmount.subtract(actualAmount).abs();
            boolean amountMatches = amountDifference.compareTo(TOLERANCE) <= 0;

            if (!amountMatches) {
                LOGGER.warnf("[Tenant: %s] Payout amount mismatch: expected=%s, actual=%s, difference=%s, tolerance=%s",
                        tenantTag, expectedAmount, actualAmount, amountDifference, TOLERANCE);
                payoutBatch.status = "pending_review";
                meterRegistry.counter("payments.payout.reconciliation.discrepancy", "tenant", tenantTag,
                        "discrepancy_type", "amount_mismatch").increment();
            } else if ("paid".equals(payoutStatus) || "in_transit".equals(payoutStatus)) {
                payoutBatch.status = "completed";
                payoutBatch.processedAt = OffsetDateTime.now();
                LOGGER.infof("[Tenant: %s] Payout reconciliation successful: batchId=%s, status=%s", tenantTag,
                        payoutBatchId, payoutStatus);
            } else if ("failed".equals(payoutStatus) || "canceled".equals(payoutStatus)) {
                payoutBatch.status = "failed";
                payoutBatch.processedAt = OffsetDateTime.now();
                LOGGER.warnf("[Tenant: %s] Payout failed at provider: batchId=%s, providerStatus=%s", tenantTag,
                        payoutBatchId, payoutStatus);
                meterRegistry.counter("payments.payout.reconciliation.discrepancy", "tenant", tenantTag,
                        "discrepancy_type", "provider_failure").increment();
            } else {
                LOGGER.infof("[Tenant: %s] Payout still pending: batchId=%s, status=%s", tenantTag, payoutBatchId,
                        payoutStatus);
            }

            meterRegistry.counter("payments.payout.reconciliation.completed", "tenant", tenantTag).increment();

        } catch (Exception e) {
            LOGGER.errorf(e, "[Tenant: %s] Payout reconciliation failed", tenantTag);
            meterRegistry.counter("payments.payout.reconciliation.failed", "tenant", tenantTag, "reason", "exception")
                    .increment();
            throw e;
        } finally {
            sample.stop(meterRegistry.timer("payments.payout.reconciliation.duration", "tenant", tenantTag));
        }
    }
}
