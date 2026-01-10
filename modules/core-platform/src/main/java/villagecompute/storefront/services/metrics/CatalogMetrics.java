package villagecompute.storefront.services.metrics;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized metrics helper for catalog domain operations.
 *
 * <p>
 * Provides consistent metric naming and tagging for catalog operations including products, variants, categories,
 * collections, and import/export jobs. All metrics include tenant_id tags for multi-tenant observability.
 *
 * <p>
 * Metric Types:
 * <ul>
 * <li>Counters: Operation counts (create, update, delete, search)</li>
 * <li>Timers: Operation latency histograms with percentiles</li>
 * <li>Distribution Summaries: Result set sizes, page counts</li>
 * </ul>
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
public class CatalogMetrics {

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // Product Metrics
    // ========================================

    /**
     * Record product creation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordProductCreated(UUID tenantId) {
        counter("catalog.product.created", tenantId).increment();
    }

    /**
     * Record product update.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordProductUpdated(UUID tenantId) {
        counter("catalog.product.updated", tenantId).increment();
    }

    /**
     * Record product deletion.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordProductDeleted(UUID tenantId) {
        counter("catalog.product.deleted", tenantId).increment();
    }

    /**
     * Record product search duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            search duration in milliseconds
     */
    public void recordProductSearch(UUID tenantId, long durationMillis) {
        timer("catalog.product.search.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record product search result size.
     *
     * @param tenantId
     *            tenant identifier
     * @param resultCount
     *            number of products returned
     */
    public void recordProductSearchResults(UUID tenantId, int resultCount) {
        distributionSummary("catalog.product.search.results", tenantId).record(resultCount);
    }

    // ========================================
    // Variant Metrics
    // ========================================

    /**
     * Record variant creation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordVariantCreated(UUID tenantId) {
        counter("catalog.variant.created", tenantId).increment();
    }

    /**
     * Record variant update.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordVariantUpdated(UUID tenantId) {
        counter("catalog.variant.updated", tenantId).increment();
    }

    /**
     * Record variant deletion.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordVariantDeleted(UUID tenantId) {
        counter("catalog.variant.deleted", tenantId).increment();
    }

    // ========================================
    // Category Metrics
    // ========================================

    /**
     * Record category creation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCategoryCreated(UUID tenantId) {
        counter("catalog.category.created", tenantId).increment();
    }

    /**
     * Record category update.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCategoryUpdated(UUID tenantId) {
        counter("catalog.category.updated", tenantId).increment();
    }

    /**
     * Record category deletion.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCategoryDeleted(UUID tenantId) {
        counter("catalog.category.deleted", tenantId).increment();
    }

    // ========================================
    // Collection Metrics
    // ========================================

    /**
     * Record collection creation.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCollectionCreated(UUID tenantId) {
        counter("catalog.collection.created", tenantId).increment();
    }

    /**
     * Record collection update.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCollectionUpdated(UUID tenantId) {
        counter("catalog.collection.updated", tenantId).increment();
    }

    /**
     * Record collection deletion.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordCollectionDeleted(UUID tenantId) {
        counter("catalog.collection.deleted", tenantId).increment();
    }

    // ========================================
    // KPI Metrics (Component Performance Indicators)
    // ========================================

    /**
     * Record bulk import throughput (products per minute).
     *
     * <p>
     * Target KPI: >5000 products/min (Foundation §4.3)
     *
     * @param tenantId
     *            tenant identifier
     * @param productsPerMinute
     *            current throughput in products/minute
     */
    public void recordBulkImportThroughput(UUID tenantId, int productsPerMinute) {
        meterRegistry.gauge("catalog.bulk.import.throughput",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString())),
                productsPerMinute);
    }

    /**
     * Record variant upsert batch operation.
     *
     * <p>
     * Target KPI: p95 latency <200ms (Foundation §4.3)
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            batch processing duration in milliseconds
     * @param batchSize
     *            number of variants in batch
     */
    public void recordVariantUpsertBatch(UUID tenantId, long durationMillis, int batchSize) {
        timer("catalog.variant.upsert.batch.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        distributionSummary("catalog.variant.upsert.batch.size", tenantId).record(batchSize);
    }

    /**
     * Record category search operation.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            search duration in milliseconds
     */
    public void recordCategorySearch(UUID tenantId, long durationMillis) {
        timer("catalog.category.search.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record cache hit.
     *
     * @param tenantId
     *            tenant identifier
     * @param cacheType
     *            cache type (e.g., "product", "category", "collection")
     */
    public void recordCacheHit(UUID tenantId, String cacheType) {
        meterRegistry.counter("catalog.cache.hit", "tenant_id", tenantId.toString(), "cache_type", cacheType)
                .increment();
    }

    /**
     * Record cache miss.
     *
     * @param tenantId
     *            tenant identifier
     * @param cacheType
     *            cache type (e.g., "product", "category", "collection")
     */
    public void recordCacheMiss(UUID tenantId, String cacheType) {
        meterRegistry.counter("catalog.cache.miss", "tenant_id", tenantId.toString(), "cache_type", cacheType)
                .increment();
    }

    /**
     * Record validation error during catalog operations.
     *
     * @param tenantId
     *            tenant identifier
     * @param errorType
     *            error type (e.g., "invalid_sku", "duplicate_slug", "missing_required_field")
     */
    public void recordValidationError(UUID tenantId, String errorType) {
        meterRegistry.counter("catalog.validation.error", "tenant_id", tenantId.toString(), "error_type", errorType)
                .increment();
    }

    // ========================================
    // Import/Export Job Metrics
    // ========================================

    /**
     * Record catalog import job enqueue.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordImportEnqueued(UUID tenantId) {
        counter("catalog.import.enqueued", tenantId).increment();
    }

    /**
     * Record catalog import job success.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            processing duration in milliseconds
     * @param itemsProcessed
     *            number of items imported
     */
    public void recordImportSuccess(UUID tenantId, long durationMillis, int itemsProcessed) {
        counter("catalog.import.success", tenantId).increment();
        timer("catalog.import.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        distributionSummary("catalog.import.items", tenantId).record(itemsProcessed);
    }

    /**
     * Record catalog import job failure.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            failure reason (e.g., "validation_error", "file_not_found")
     */
    public void recordImportFailure(UUID tenantId, String reason) {
        meterRegistry.counter("catalog.import.failed", "tenant_id", tenantId.toString(), "reason", reason).increment();
    }

    /**
     * Record catalog export job enqueue.
     *
     * @param tenantId
     *            tenant identifier
     */
    public void recordExportEnqueued(UUID tenantId) {
        counter("catalog.export.enqueued", tenantId).increment();
    }

    /**
     * Record catalog export job success.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            processing duration in milliseconds
     * @param itemsExported
     *            number of items exported
     */
    public void recordExportSuccess(UUID tenantId, long durationMillis, int itemsExported) {
        counter("catalog.export.success", tenantId).increment();
        timer("catalog.export.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
        distributionSummary("catalog.export.items", tenantId).record(itemsExported);
    }

    /**
     * Record catalog export job failure.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            failure reason (e.g., "storage_error", "permission_denied")
     */
    public void recordExportFailure(UUID tenantId, String reason) {
        meterRegistry.counter("catalog.export.failed", "tenant_id", tenantId.toString(), "reason", reason).increment();
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

    /**
     * Get or create a distribution summary with tenant_id tag.
     *
     * @param name
     *            metric name
     * @param tenantId
     *            tenant identifier
     * @return distribution summary instance
     */
    private DistributionSummary distributionSummary(String name, UUID tenantId) {
        return meterRegistry.summary(name, "tenant_id", tenantId.toString());
    }
}
