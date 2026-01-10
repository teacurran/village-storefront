package villagecompute.storefront.services.metrics;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized metrics helper for consignment vendor and payout operations.
 *
 * <p>
 * Provides consistent metric naming and tagging for consignment operations including sales tracking, payout processing,
 * ledger operations, and settlement. All metrics include tenant_id tags for multi-tenant observability.
 *
 * <p>
 * Metric Types:
 * <ul>
 * <li>Counters: Operation counts (sales, payouts, ledger entries)</li>
 * <li>Timers: Operation latency histograms (payout batch processing, settlement)</li>
 * <li>Gauges: Current state (pending balances, available balances)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Observability instrumentation for I5 modules</li>
 * <li>Architecture §3.7: Observability Fabric</li>
 * <li>Architecture §3.12: KPI Tracking (payout reconciliation duration)</li>
 * <li>Architecture §3.20.2: Sev2 Alert for payout reconciliation delayed beyond daily window</li>
 * <li>Foundation §3.0: Rulebook (structured logging + metrics)</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentMetrics {

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // Vendor Management Metrics
    // ========================================

    /**
     * Record consignment vendor created.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordVendorCreated(UUID tenantId) {
        counter("consignment.vendor.created", tenantId).increment();
    }

    /**
     * Record vendor status change.
     *
     * @param tenantId
     *            tenant identifier
     * @param fromStatus
     *            previous status
     * @param toStatus
     *            new status (e.g., "pending", "active", "suspended")
     */
    public void recordVendorStatusChange(UUID tenantId, String fromStatus, String toStatus) {
        meterRegistry.counter("consignment.vendor.status_changed", "tenant_id", tenantId.toString(), "from_status",
                fromStatus, "to_status", toStatus).increment();
    }

    /**
     * Record vendor onboarding completion.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            time from creation to active status in milliseconds
     */
    public void recordVendorOnboardingCompleted(UUID tenantId, long durationMillis) {
        counter("consignment.vendor.onboarding.completed", tenantId).increment();
        timer("consignment.vendor.onboarding.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // ========================================
    // Sales and Commission Metrics
    // ========================================

    /**
     * Record consignment sale recorded.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param saleAmountCents
     *            sale amount in cents
     * @param commissionRatePercent
     *            platform commission rate percentage
     */
    public void recordSale(UUID tenantId, UUID vendorId, long saleAmountCents, double commissionRatePercent) {
        counter("consignment.sale.recorded", tenantId).increment();
        meterRegistry.counter("consignment.sale.amount", "tenant_id", tenantId.toString())
                .increment(saleAmountCents / 100.0);

        double commissionAmountCents = saleAmountCents * (commissionRatePercent / 100.0);
        meterRegistry.counter("consignment.commission.earned", "tenant_id", tenantId.toString())
                .increment(commissionAmountCents / 100.0);
    }

    /**
     * Record sale refund.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param refundAmountCents
     *            refund amount in cents
     */
    public void recordSaleRefund(UUID tenantId, UUID vendorId, long refundAmountCents) {
        counter("consignment.sale.refunded", tenantId).increment();
        meterRegistry.counter("consignment.sale.refund_amount", "tenant_id", tenantId.toString())
                .increment(refundAmountCents / 100.0);
    }

    // ========================================
    // Ledger Operation Metrics
    // ========================================

    /**
     * Record ledger entry created (double-entry bookkeeping).
     *
     * @param tenantId
     *            tenant identifier
     * @param entryType
     *            entry type (e.g., "sale", "payout", "adjustment", "settlement")
     */
    public void recordLedgerEntry(UUID tenantId, String entryType) {
        meterRegistry
                .counter("consignment.ledger.entry.created", "tenant_id", tenantId.toString(), "entry_type", entryType)
                .increment();
    }

    /**
     * Record ledger balance update.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param pendingBalanceCents
     *            current pending balance in cents
     * @param availableBalanceCents
     *            current available balance in cents
     */
    public void recordLedgerBalance(UUID tenantId, UUID vendorId, long pendingBalanceCents,
            long availableBalanceCents) {
        meterRegistry.gauge("consignment.ledger.balance.pending",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString()),
                        io.micrometer.core.instrument.Tag.of("vendor_id", vendorId.toString())),
                pendingBalanceCents / 100.0);

        meterRegistry.gauge("consignment.ledger.balance.available",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString()),
                        io.micrometer.core.instrument.Tag.of("vendor_id", vendorId.toString())),
                availableBalanceCents / 100.0);
    }

    /**
     * Record settlement operation (moving pending to available).
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param settledAmountCents
     *            amount settled in cents
     * @param durationMillis
     *            settlement processing duration in milliseconds
     */
    public void recordSettlement(UUID tenantId, UUID vendorId, long settledAmountCents, long durationMillis) {
        counter("consignment.ledger.settlement.completed", tenantId).increment();
        meterRegistry.counter("consignment.ledger.settlement.amount", "tenant_id", tenantId.toString())
                .increment(settledAmountCents / 100.0);
        timer("consignment.ledger.settlement.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record ledger reconciliation discrepancy.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param discrepancyCents
     *            discrepancy amount in cents (absolute value)
     */
    public void recordLedgerDiscrepancy(UUID tenantId, UUID vendorId, long discrepancyCents) {
        meterRegistry.counter("consignment.ledger.discrepancy", "tenant_id", tenantId.toString(), "vendor_id",
                vendorId.toString()).increment();
        meterRegistry.counter("consignment.ledger.discrepancy.amount", "tenant_id", tenantId.toString())
                .increment(Math.abs(discrepancyCents) / 100.0);
    }

    // ========================================
    // Payout Processing Metrics
    // ========================================

    /**
     * Record payout batch creation.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorCount
     *            number of vendors in batch
     */
    public void recordPayoutBatchCreated(UUID tenantId, int vendorCount) {
        counter("consignment.payout.batch.created", tenantId).increment();
        meterRegistry.counter("consignment.payout.batch.vendors", "tenant_id", tenantId.toString())
                .increment(vendorCount);
    }

    /**
     * Record individual payout created.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param payoutAmountCents
     *            payout amount in cents
     */
    public void recordPayoutCreated(UUID tenantId, UUID vendorId, long payoutAmountCents) {
        counter("consignment.payout.created", tenantId).increment();
        meterRegistry.counter("consignment.payout.amount", "tenant_id", tenantId.toString())
                .increment(payoutAmountCents / 100.0);
    }

    /**
     * Record payout success.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     */
    public void recordPayoutSuccess(UUID tenantId, UUID vendorId) {
        counter("consignment.payout.success", tenantId).increment();
    }

    /**
     * Record payout failure.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param failureReason
     *            failure reason (e.g., "insufficient_funds", "account_invalid", "stripe_error")
     */
    public void recordPayoutFailed(UUID tenantId, UUID vendorId, String failureReason) {
        meterRegistry.counter("consignment.payout.failed", "tenant_id", tenantId.toString(), "vendor_id",
                vendorId.toString(), "reason", failureReason).increment();
    }

    /**
     * Record payout batch processing duration.
     *
     * <p>
     * Target KPI: Monitor for Sev2 alert if delayed beyond daily window per Architecture §3.20.2
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            batch processing duration in milliseconds
     * @param payoutCount
     *            number of payouts processed
     */
    public void recordPayoutBatchProcessing(UUID tenantId, long durationMillis, int payoutCount) {
        timer("consignment.payout.batch.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        counter("consignment.payout.batch.processed", tenantId).increment(payoutCount);
    }

    /**
     * Record payout reconciliation job execution.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            reconciliation duration in milliseconds
     * @param recordsReconciled
     *            number of records reconciled
     */
    public void recordPayoutReconciliation(UUID tenantId, long durationMillis, int recordsReconciled) {
        timer("consignment.payout.reconciliation.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        counter("consignment.payout.reconciliation.records", tenantId).increment(recordsReconciled);
    }

    /**
     * Record payout retry attempt.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param attemptNumber
     *            retry attempt number
     */
    public void recordPayoutRetry(UUID tenantId, UUID vendorId, int attemptNumber) {
        meterRegistry.counter("consignment.payout.retry", "tenant_id", tenantId.toString(), "vendor_id",
                vendorId.toString(), "attempt", String.valueOf(attemptNumber)).increment();
    }

    // ========================================
    // KPI Metrics (Component Performance Indicators)
    // ========================================

    /**
     * Record aggregate pending payout liability across all vendors.
     *
     * @param tenantId
     *            tenant identifier
     * @param totalPendingCents
     *            total pending payout liability in cents
     */
    public void recordTotalPendingLiability(UUID tenantId, long totalPendingCents) {
        meterRegistry.gauge("consignment.payout.pending.total",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString())),
                totalPendingCents / 100.0);
    }

    /**
     * Record aggregate available payout balance across all vendors.
     *
     * @param tenantId
     *            tenant identifier
     * @param totalAvailableCents
     *            total available payout balance in cents
     */
    public void recordTotalAvailableBalance(UUID tenantId, long totalAvailableCents) {
        meterRegistry.gauge("consignment.payout.available.total",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString())),
                totalAvailableCents / 100.0);
    }

    /**
     * Record vendor payout schedule adherence.
     *
     * @param tenantId
     *            tenant identifier
     * @param scheduleType
     *            schedule type (e.g., "daily", "weekly", "monthly")
     * @param onTime
     *            whether payout was on time
     */
    public void recordPayoutScheduleAdherence(UUID tenantId, String scheduleType, boolean onTime) {
        String status = onTime ? "on_time" : "delayed";
        meterRegistry.counter("consignment.payout.schedule.adherence", "tenant_id", tenantId.toString(),
                "schedule_type", scheduleType, "status", status).increment();
    }

    /**
     * Record automated payout batch triggered.
     *
     * @param tenantId
     *            tenant identifier
     * @param triggerType
     *            trigger type (e.g., "scheduled", "threshold_met", "manual")
     */
    public void recordPayoutBatchTriggered(UUID tenantId, String triggerType) {
        meterRegistry.counter("consignment.payout.batch.triggered", "tenant_id", tenantId.toString(), "trigger_type",
                triggerType).increment();
    }

    // ========================================
    // Commission Calculation Metrics
    // ========================================

    /**
     * Record commission rate override applied.
     *
     * @param tenantId
     *            tenant identifier
     * @param vendorId
     *            vendor identifier
     * @param defaultRate
     *            default commission rate percentage
     * @param overrideRate
     *            override commission rate percentage
     */
    public void recordCommissionOverride(UUID tenantId, UUID vendorId, double defaultRate, double overrideRate) {
        meterRegistry.counter("consignment.commission.override", "tenant_id", tenantId.toString(), "vendor_id",
                vendorId.toString()).increment();
    }

    /**
     * Record commission calculation error.
     *
     * @param tenantId
     *            tenant identifier
     * @param errorType
     *            error type (e.g., "rate_not_found", "invalid_amount", "negative_commission")
     */
    public void recordCommissionError(UUID tenantId, String errorType) {
        meterRegistry.counter("consignment.commission.error", "tenant_id", tenantId.toString(), "error_type", errorType)
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
