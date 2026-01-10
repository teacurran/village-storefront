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
 * Centralized metrics helper for inventory domain operations.
 *
 * <p>
 * Provides consistent metric naming and tagging for multi-location inventory operations including adjustments,
 * reservations, transfers, and receipts. All metrics include tenant_id tags, with selective location tags where
 * cardinality permits.
 *
 * <p>
 * Metric Types:
 * <ul>
 * <li>Counters: Operation counts (adjustments, reservations, commits, transfers)</li>
 * <li>Timers: Operation latency histograms (transfer processing, adjustment duration)</li>
 * <li>Gauges: Current state (reserved quantities, pending transfers)</li>
 * </ul>
 *
 * <p>
 * Cardinality Note: Location tags are used sparingly to avoid metric explosion. Only high-level location-scoped
 * operations (transfers, adjustments) include location labels.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Observability instrumentation</li>
 * <li>Architecture §3.7: Observability Fabric</li>
 * <li>Foundation §3.0: Rulebook (structured logging + metrics)</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryMetrics {

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // Inventory Level Metrics
    // ========================================

    /**
     * Record inventory level update.
     *
     * @param tenantId
     *            tenant identifier
     * @param location
     *            location identifier
     */
    public void recordLevelUpdated(UUID tenantId, String location) {
        meterRegistry.counter("inventory.level.updated", "tenant_id", tenantId.toString(), "location", location)
                .increment();
    }

    /**
     * Record inventory adjustment.
     *
     * @param tenantId
     *            tenant identifier
     * @param location
     *            location identifier
     * @param reason
     *            adjustment reason (e.g., "damaged", "found", "cycle_count")
     * @param quantityChange
     *            quantity change (positive or negative)
     */
    public void recordAdjustment(UUID tenantId, String location, String reason, int quantityChange) {
        meterRegistry.counter("inventory.adjustment", "tenant_id", tenantId.toString(), "location", location, "reason",
                reason).increment();

        String direction = quantityChange >= 0 ? "increase" : "decrease";
        double amount = Math.abs((double) quantityChange);
        meterRegistry.counter("inventory.adjustment.quantity", "tenant_id", tenantId.toString(), "location", location,
                "direction", direction).increment(amount);
    }

    /**
     * Record adjustment processing duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            processing duration in milliseconds
     */
    public void recordAdjustmentDuration(UUID tenantId, long durationMillis) {
        timer("inventory.adjustment.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    // ========================================
    // Reservation Metrics
    // ========================================

    /**
     * Record inventory reservation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordReserved(UUID tenantId, String location, int quantity) {
        meterRegistry.counter("inventory.reserved", "tenant_id", tenantId.toString(), "location", location)
                .increment(Math.max(quantity, 0));
    }

    /**
     * Record inventory reservation commit.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCommitted(UUID tenantId, String location, int quantity) {
        meterRegistry.counter("inventory.committed", "tenant_id", tenantId.toString(), "location", location)
                .increment(Math.max(quantity, 0));
    }

    /**
     * Record inventory reservation release.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordReleased(UUID tenantId, String location, int quantity) {
        meterRegistry.counter("inventory.released", "tenant_id", tenantId.toString(), "location", location)
                .increment(Math.max(quantity, 0));
    }

    /**
     * Record failed reservation attempt.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            failure reason (e.g., "insufficient_stock", "location_unavailable")
     */
    public void recordReservationFailed(UUID tenantId, String reason) {
        meterRegistry.counter("inventory.reservation.failed", "tenant_id", tenantId.toString(), "reason", reason)
                .increment();
    }

    // ========================================
    // Transfer Metrics
    // ========================================

    /**
     * Record inventory transfer creation.
     *
     * @param tenantId
     *            tenant identifier
     * @param sourceLocation
     *            source location code
     * @param destinationLocation
     *            destination location code
     */
    public void recordTransferCreated(UUID tenantId, String sourceLocation, String destinationLocation) {
        meterRegistry.counter("inventory.transfer.created", "tenant_id", tenantId.toString(), "source", sourceLocation,
                "destination", destinationLocation).increment();
    }

    /**
     * Record inventory transfer receipt.
     *
     * @param tenantId
     *            tenant identifier
     * @param destinationLocation
     *            destination location code
     */
    public void recordTransferReceived(UUID tenantId, String destinationLocation) {
        meterRegistry.counter("inventory.transfer.received", "tenant_id", tenantId.toString(), "destination",
                destinationLocation).increment();
    }

    /**
     * Record transfer processing duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            processing duration in milliseconds
     */
    public void recordTransferDuration(UUID tenantId, long durationMillis) {
        timer("inventory.transfer.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record transfer cancellation.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            cancellation reason
     */
    public void recordTransferCancelled(UUID tenantId, String reason) {
        meterRegistry.counter("inventory.transfer.cancelled", "tenant_id", tenantId.toString(), "reason", reason)
                .increment();
    }

    /**
     * Register gauge for pending transfers count.
     *
     * <p>
     * This should be called with a state object that tracks pending transfer counts.
     *
     * @param tenantId
     *            tenant identifier
     * @param stateObject
     *            object providing pending transfer count
     * @param valueFunction
     *            function to extract pending count from state object
     * @param <T>
     *            state object type
     */
    public <T> void registerPendingTransfersGauge(UUID tenantId, T stateObject,
            java.util.function.ToDoubleFunction<T> valueFunction) {
        Gauge.builder("inventory.transfer.pending", stateObject, valueFunction).tag("tenant_id", tenantId.toString())
                .description("Number of pending inventory transfers").register(meterRegistry);
    }

    // ========================================
    // KPI Metrics (Component Performance Indicators)
    // ========================================

    /**
     * Record adjustment operation latency.
     *
     * <p>
     * Tracks the full adjustment operation including RLS policy checks, validation, and database commits.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            operation duration in milliseconds
     */
    public void recordAdjustmentOperation(UUID tenantId, long durationMillis) {
        timer("inventory.adjustment.operation.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record transfer operation latency and quantity.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            operation duration in milliseconds
     * @param quantity
     *            quantity transferred
     */
    public void recordTransferOperation(UUID tenantId, long durationMillis, int quantity) {
        timer("inventory.transfer.operation.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        meterRegistry.counter("inventory.transfer.quantity", "tenant_id", tenantId.toString()).increment(quantity);
    }

    /**
     * Record insufficient stock error.
     *
     * <p>
     * Target KPI: Error rate <1% (Foundation §4.3)
     *
     * @param tenantId
     *            tenant identifier
     * @param variantId
     *            variant identifier
     */
    public void recordInsufficientStockError(UUID tenantId, UUID variantId) {
        meterRegistry.counter("inventory.error.insufficient_stock", "tenant_id", tenantId.toString(), "variant_id",
                variantId.toString()).increment();
    }

    /**
     * Record negative inventory warning.
     *
     * @param tenantId
     *            tenant identifier
     * @param variantId
     *            variant identifier
     * @param level
     *            current negative inventory level
     */
    public void recordNegativeInventoryWarning(UUID tenantId, UUID variantId, int level) {
        meterRegistry.counter("inventory.warning.negative_level", "tenant_id", tenantId.toString(), "variant_id",
                variantId.toString()).increment();
        meterRegistry.gauge("inventory.level.negative",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString()),
                        io.micrometer.core.instrument.Tag.of("variant_id", variantId.toString())),
                level);
    }

    /**
     * Record current inventory level (real-time gauge).
     *
     * @param tenantId
     *            tenant identifier
     * @param location
     *            location identifier
     * @param variantId
     *            variant identifier
     * @param currentLevel
     *            current inventory level
     */
    public void recordInventoryLevel(UUID tenantId, String location, UUID variantId, int currentLevel) {
        meterRegistry.gauge("inventory.level.current",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString()),
                        io.micrometer.core.instrument.Tag.of("location", location),
                        io.micrometer.core.instrument.Tag.of("variant_id", variantId.toString())),
                currentLevel);
    }

    // ========================================
    // Stock Availability Metrics
    // ========================================

    /**
     * Record stock check operation.
     *
     * @param tenantId
     *            tenant identifier
     * @param available
     *            whether stock was available
     */
    public void recordStockCheck(UUID tenantId, boolean available) {
        String result = available ? "available" : "unavailable";
        meterRegistry.counter("inventory.stock.check", "tenant_id", tenantId.toString(), "result", result).increment();
    }

    /**
     * Record out-of-stock event.
     *
     * @param tenantId
     *            tenant identifier
     * @param location
     *            location identifier
     */
    public void recordOutOfStock(UUID tenantId, String location) {
        meterRegistry.counter("inventory.stock.out_of_stock", "tenant_id", tenantId.toString(), "location", location)
                .increment();
    }

    /**
     * Record low stock warning event.
     *
     * @param tenantId
     *            tenant identifier
     * @param location
     *            location identifier
     */
    public void recordLowStock(UUID tenantId, String location) {
        meterRegistry.counter("inventory.stock.low_stock", "tenant_id", tenantId.toString(), "location", location)
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
