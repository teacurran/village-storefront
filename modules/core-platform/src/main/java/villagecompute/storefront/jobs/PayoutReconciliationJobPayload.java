package villagecompute.storefront.jobs;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.Tenant;

/**
 * Typed payload for payout reconciliation jobs. Encapsulates all context necessary to reconcile a payout batch with the
 * upstream provider.
 */
public record PayoutReconciliationJobPayload(UUID jobId, UUID tenantId, UUID payoutBatchId, String providerPayoutId,
        BigDecimal expectedAmount, String currency, LocalDate periodStart, LocalDate periodEnd, UUID consignorId,
        String idempotencyToken, Instant createdAt, int version) {

    private static final int SCHEMA_VERSION = 1;

    /**
     * Build payload from an existing {@link PayoutBatch}.
     *
     * @param batch
     *            payout batch entity
     * @param providerPayoutId
     *            Stripe payout identifier
     * @return job payload
     */
    public static PayoutReconciliationJobPayload fromBatch(PayoutBatch batch, String providerPayoutId) {
        UUID jobId = UUID.randomUUID();
        Tenant tenant = batch.tenant;
        Consignor consignor = batch.consignor;

        BigDecimal expectedAmount = batch.totalAmount != null ? batch.totalAmount : BigDecimal.ZERO;
        String currency = (batch.currency != null && !batch.currency.isBlank()) ? batch.currency : "USD";

        return new PayoutReconciliationJobPayload(jobId, tenant != null ? tenant.id : null, batch.id, providerPayoutId,
                expectedAmount, currency, batch.periodStart, batch.periodEnd, consignor != null ? consignor.id : null,
                "payout-" + batch.id, Instant.now(), SCHEMA_VERSION);
    }
}
