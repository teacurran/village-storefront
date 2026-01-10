package villagecompute.storefront.services.metrics;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized metrics helper for loyalty program operations.
 *
 * <p>
 * Provides consistent metric naming and tagging for loyalty operations including points accrual, redemption, tier
 * calculations, and expiration processing. All metrics include tenant_id tags for multi-tenant observability.
 *
 * <p>
 * Metric Types:
 * <ul>
 * <li>Counters: Operation counts (accrual, redemption, expiration, tier changes)</li>
 * <li>Timers: Operation latency histograms (tier recalculation, batch processing)</li>
 * <li>Gauges: Current state (active points balances, queue depths)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Observability instrumentation for I5 modules</li>
 * <li>Architecture §3.7: Observability Fabric</li>
 * <li>Architecture §3.12: KPI Tracking and Telemetry-Driven Feedback Loops</li>
 * <li>Foundation §3.0: Rulebook (structured logging + metrics)</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyMetrics {

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // Points Accrual Metrics
    // ========================================

    /**
     * Record points accrual operation.
     *
     * @param tenantId
     *            tenant identifier
     * @param pointsAmount
     *            number of points accrued
     */
    public void recordPointsAccrued(UUID tenantId, int pointsAmount) {
        counter("loyalty.points.accrued", tenantId).increment(pointsAmount);
        counter("loyalty.accrual.operations", tenantId).increment();
    }

    /**
     * Record points accrual duration.
     *
     * <p>
     * Target KPI: p95 latency <100ms (Architecture §3.12)
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            accrual processing duration in milliseconds
     */
    public void recordAccrualDuration(UUID tenantId, long durationMillis) {
        timer("loyalty.accrual.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record failed accrual attempt.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            failure reason (e.g., "customer_not_found", "program_inactive")
     */
    public void recordAccrualFailed(UUID tenantId, String reason) {
        meterRegistry.counter("loyalty.accrual.failed", "tenant_id", tenantId.toString(), "reason", reason).increment();
    }

    // ========================================
    // Points Redemption Metrics
    // ========================================

    /**
     * Record points redemption operation.
     *
     * @param tenantId
     *            tenant identifier
     * @param pointsAmount
     *            number of points redeemed
     */
    public void recordPointsRedeemed(UUID tenantId, int pointsAmount) {
        counter("loyalty.points.redeemed", tenantId).increment(pointsAmount);
        counter("loyalty.redemption.operations", tenantId).increment();
    }

    /**
     * Record points reservation (hold during checkout).
     *
     * @param tenantId
     *            tenant identifier
     * @param pointsAmount
     *            number of points reserved
     */
    public void recordPointsReserved(UUID tenantId, int pointsAmount) {
        counter("loyalty.points.reserved", tenantId).increment(pointsAmount);
    }

    /**
     * Record points reservation release (checkout cancelled/expired).
     *
     * @param tenantId
     *            tenant identifier
     * @param pointsAmount
     *            number of points released
     */
    public void recordPointsReleased(UUID tenantId, int pointsAmount) {
        counter("loyalty.points.released", tenantId).increment(pointsAmount);
    }

    /**
     * Record redemption duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            redemption processing duration in milliseconds
     */
    public void recordRedemptionDuration(UUID tenantId, long durationMillis) {
        timer("loyalty.redemption.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record failed redemption attempt.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            failure reason (e.g., "insufficient_points", "expired_points", "program_inactive")
     */
    public void recordRedemptionFailed(UUID tenantId, String reason) {
        meterRegistry.counter("loyalty.redemption.failed", "tenant_id", tenantId.toString(), "reason", reason)
                .increment();
    }

    // ========================================
    // Tier Management Metrics
    // ========================================

    /**
     * Record tier change event.
     *
     * @param tenantId
     *            tenant identifier
     * @param fromTier
     *            previous tier name
     * @param toTier
     *            new tier name
     */
    public void recordTierChange(UUID tenantId, String fromTier, String toTier) {
        meterRegistry.counter("loyalty.tier.changed", "tenant_id", tenantId.toString(), "from_tier", fromTier,
                "to_tier", toTier).increment();
    }

    /**
     * Record nightly tier recalculation batch operation.
     *
     * <p>
     * Target KPI: Duration <15 minutes per Architecture §3.20.2
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            recalculation duration in milliseconds
     * @param customerCount
     *            number of customers processed
     */
    public void recordTierRecalculation(UUID tenantId, long durationMillis, int customerCount) {
        timer("loyalty.tier.recalculation.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        meterRegistry.counter("loyalty.tier.recalculation.customers", "tenant_id", tenantId.toString())
                .increment(customerCount);
    }

    /**
     * Record tier distribution for observability.
     *
     * @param tenantId
     *            tenant identifier
     * @param tierName
     *            tier name
     * @param customerCount
     *            number of customers in this tier
     */
    public void recordTierDistribution(UUID tenantId, String tierName, int customerCount) {
        meterRegistry.gauge("loyalty.tier.customers",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString()),
                        io.micrometer.core.instrument.Tag.of("tier", tierName)),
                customerCount);
    }

    // ========================================
    // Points Expiration Metrics
    // ========================================

    /**
     * Record points expiration event.
     *
     * @param tenantId
     *            tenant identifier
     * @param pointsAmount
     *            number of points expired
     */
    public void recordPointsExpired(UUID tenantId, int pointsAmount) {
        counter("loyalty.points.expired", tenantId).increment(pointsAmount);
    }

    /**
     * Record expiration job processing.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            job duration in milliseconds
     * @param recordsProcessed
     *            number of expiration records processed
     */
    public void recordExpirationJob(UUID tenantId, long durationMillis, int recordsProcessed) {
        timer("loyalty.expiration.job.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        counter("loyalty.expiration.job.records", tenantId).increment(recordsProcessed);
    }

    /**
     * Record reservation expiration (abandoned checkout).
     *
     * @param tenantId
     *            tenant identifier
     * @param reservationsReleased
     *            number of expired reservations released
     */
    public void recordReservationExpiration(UUID tenantId, int reservationsReleased) {
        counter("loyalty.reservation.expired", tenantId).increment(reservationsReleased);
    }

    // ========================================
    // Queue and Job Metrics
    // ========================================

    /**
     * Register gauge for loyalty processing queue depth.
     *
     * <p>
     * Target KPI: Queue backlog monitoring per Architecture §3.12
     *
     * @param tenantId
     *            tenant identifier
     * @param stateObject
     *            object providing queue depth
     * @param valueFunction
     *            function to extract queue depth from state object
     * @param <T>
     *            state object type
     */
    public <T> void registerQueueDepthGauge(UUID tenantId, T stateObject,
            java.util.function.ToDoubleFunction<T> valueFunction) {
        Gauge.builder("loyalty.queue.depth", stateObject, valueFunction).tag("tenant_id", tenantId.toString())
                .description("Number of pending loyalty processing jobs").register(meterRegistry);
    }

    /**
     * Record background job execution.
     *
     * @param tenantId
     *            tenant identifier
     * @param jobType
     *            job type (e.g., "tier_recalculation", "expiration_cleanup", "reservation_release")
     * @param durationMillis
     *            job duration in milliseconds
     * @param success
     *            whether job succeeded
     */
    public void recordJobExecution(UUID tenantId, String jobType, long durationMillis, boolean success) {
        String status = success ? "success" : "failed";
        meterRegistry.counter("loyalty.job.executed", "tenant_id", tenantId.toString(), "job_type", jobType, "status",
                status).increment();
        meterRegistry.timer("loyalty.job.duration", "tenant_id", tenantId.toString(), "job_type", jobType)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // ========================================
    // KPI Metrics (Component Performance Indicators)
    // ========================================

    /**
     * Record current active points balance across all customers.
     *
     * @param tenantId
     *            tenant identifier
     * @param totalActivePoints
     *            sum of all active points
     */
    public void recordActivePointsBalance(UUID tenantId, long totalActivePoints) {
        meterRegistry.gauge("loyalty.points.active.total",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString())),
                totalActivePoints);
    }

    /**
     * Record redemption exceeding accrual anomaly (potential fraud indicator).
     *
     * <p>
     * Info alert per Architecture §3.20.3
     *
     * @param tenantId
     *            tenant identifier
     * @param customerId
     *            customer identifier with anomalous pattern
     */
    public void recordRedemptionExceedsAccrual(UUID tenantId, UUID customerId) {
        meterRegistry.counter("loyalty.anomaly.redemption_exceeds_accrual", "tenant_id", tenantId.toString(),
                "customer_id", customerId.toString()).increment();
    }

    /**
     * Record loyalty program activation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordProgramActivated(UUID tenantId) {
        counter("loyalty.program.activated", tenantId).increment();
    }

    /**
     * Record loyalty program deactivation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordProgramDeactivated(UUID tenantId) {
        counter("loyalty.program.deactivated", tenantId).increment();
    }

    /**
     * Record customer enrollment in loyalty program.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCustomerEnrolled(UUID tenantId) {
        counter("loyalty.customer.enrolled", tenantId).increment();
    }

    /**
     * Record customer opt-out from loyalty program.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCustomerOptedOut(UUID tenantId) {
        counter("loyalty.customer.opted_out", tenantId).increment();
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
