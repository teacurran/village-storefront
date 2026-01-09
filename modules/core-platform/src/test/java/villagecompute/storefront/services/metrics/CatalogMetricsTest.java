package villagecompute.storefront.services.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for {@link CatalogMetrics}.
 *
 * <p>
 * Verifies that metrics are properly registered and incremented when operations are recorded.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Observability instrumentation</li>
 * <li>Acceptance Criteria: Tests ensure metrics registered</li>
 * </ul>
 */
@QuarkusTest
class CatalogMetricsTest {

    @Inject
    CatalogMetrics catalogMetrics;

    @Inject
    MeterRegistry meterRegistry;

    private final UUID testTenantId = UUID.randomUUID();

    @Test
    void testProductCreatedMetricRegistered() {
        // When
        catalogMetrics.recordProductCreated(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.product.created").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Product created counter should be registered");
        assertTrue(counter.count() > 0, "Product created counter should be incremented");
    }

    @Test
    void testProductUpdatedMetricRegistered() {
        // When
        catalogMetrics.recordProductUpdated(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.product.updated").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Product updated counter should be registered");
        assertEquals(1.0, counter.count(), "Product updated counter should be incremented by 1");
    }

    @Test
    void testProductDeletedMetricRegistered() {
        // When
        catalogMetrics.recordProductDeleted(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.product.deleted").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Product deleted counter should be registered");
        assertEquals(1.0, counter.count(), "Product deleted counter should be incremented by 1");
    }

    @Test
    void testProductSearchDurationMetricRegistered() {
        // When
        catalogMetrics.recordProductSearch(testTenantId, 250L);

        // Then
        Timer timer = meterRegistry.find("catalog.product.search.duration").tag("tenant_id", testTenantId.toString())
                .timer();

        assertNotNull(timer, "Product search duration timer should be registered");
        assertEquals(1L, timer.count(), "Product search timer should record 1 execution");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 250.0,
                "Product search timer should record duration >= 250ms");
    }

    @Test
    void testProductSearchResultsMetricRegistered() {
        // When
        catalogMetrics.recordProductSearchResults(testTenantId, 42);

        // Then
        DistributionSummary summary = meterRegistry.find("catalog.product.search.results")
                .tag("tenant_id", testTenantId.toString()).summary();

        assertNotNull(summary, "Product search results summary should be registered");
        assertEquals(1L, summary.count(), "Product search results summary should record 1 sample");
        assertEquals(42.0, summary.totalAmount(), "Product search results summary should record 42 results");
    }

    @Test
    void testVariantCreatedMetricRegistered() {
        // When
        catalogMetrics.recordVariantCreated(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.variant.created").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Variant created counter should be registered");
        assertEquals(1.0, counter.count(), "Variant created counter should be incremented by 1");
    }

    @Test
    void testCategoryCreatedMetricRegistered() {
        // When
        catalogMetrics.recordCategoryCreated(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.category.created").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Category created counter should be registered");
        assertEquals(1.0, counter.count(), "Category created counter should be incremented by 1");
    }

    @Test
    void testCollectionCreatedMetricRegistered() {
        // When
        catalogMetrics.recordCollectionCreated(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.collection.created").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Collection created counter should be registered");
        assertEquals(1.0, counter.count(), "Collection created counter should be incremented by 1");
    }

    @Test
    void testImportEnqueuedMetricRegistered() {
        // When
        catalogMetrics.recordImportEnqueued(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.import.enqueued").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Import enqueued counter should be registered");
        assertEquals(1.0, counter.count(), "Import enqueued counter should be incremented by 1");
    }

    @Test
    void testImportSuccessMetricRegistered() {
        // When
        catalogMetrics.recordImportSuccess(testTenantId, 5000L, 100);

        // Then
        Counter counter = meterRegistry.find("catalog.import.success").tag("tenant_id", testTenantId.toString())
                .counter();
        Timer timer = meterRegistry.find("catalog.import.duration").tag("tenant_id", testTenantId.toString()).timer();
        DistributionSummary summary = meterRegistry.find("catalog.import.items")
                .tag("tenant_id", testTenantId.toString()).summary();

        assertNotNull(counter, "Import success counter should be registered");
        assertEquals(1.0, counter.count(), "Import success counter should be incremented by 1");

        assertNotNull(timer, "Import duration timer should be registered");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 5000.0,
                "Import duration should record >= 5000ms");

        assertNotNull(summary, "Import items summary should be registered");
        assertEquals(100.0, summary.totalAmount(), "Import items should record 100 items");
    }

    @Test
    void testImportFailureMetricRegistered() {
        // When
        catalogMetrics.recordImportFailure(testTenantId, "validation_error");

        // Then
        Counter counter = meterRegistry.find("catalog.import.failed").tag("tenant_id", testTenantId.toString())
                .tag("reason", "validation_error").counter();

        assertNotNull(counter, "Import failed counter should be registered");
        assertEquals(1.0, counter.count(), "Import failed counter should be incremented by 1");
    }

    @Test
    void testExportEnqueuedMetricRegistered() {
        // When
        catalogMetrics.recordExportEnqueued(testTenantId);

        // Then
        Counter counter = meterRegistry.find("catalog.export.enqueued").tag("tenant_id", testTenantId.toString())
                .counter();

        assertNotNull(counter, "Export enqueued counter should be registered");
        assertEquals(1.0, counter.count(), "Export enqueued counter should be incremented by 1");
    }

    @Test
    void testExportSuccessMetricRegistered() {
        // When
        catalogMetrics.recordExportSuccess(testTenantId, 3000L, 50);

        // Then
        Counter counter = meterRegistry.find("catalog.export.success").tag("tenant_id", testTenantId.toString())
                .counter();
        Timer timer = meterRegistry.find("catalog.export.duration").tag("tenant_id", testTenantId.toString()).timer();
        DistributionSummary summary = meterRegistry.find("catalog.export.items")
                .tag("tenant_id", testTenantId.toString()).summary();

        assertNotNull(counter, "Export success counter should be registered");
        assertEquals(1.0, counter.count(), "Export success counter should be incremented by 1");

        assertNotNull(timer, "Export duration timer should be registered");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 3000.0,
                "Export duration should record >= 3000ms");

        assertNotNull(summary, "Export items summary should be registered");
        assertEquals(50.0, summary.totalAmount(), "Export items should record 50 items");
    }

    @Test
    void testExportFailureMetricRegistered() {
        // When
        catalogMetrics.recordExportFailure(testTenantId, "storage_error");

        // Then
        Counter counter = meterRegistry.find("catalog.export.failed").tag("tenant_id", testTenantId.toString())
                .tag("reason", "storage_error").counter();

        assertNotNull(counter, "Export failed counter should be registered");
        assertEquals(1.0, counter.count(), "Export failed counter should be incremented by 1");
    }

    @Test
    void testMultipleTenantMetricsIsolation() {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        // When
        catalogMetrics.recordProductCreated(tenant1);
        catalogMetrics.recordProductCreated(tenant1);
        catalogMetrics.recordProductCreated(tenant2);

        // Then
        Counter tenant1Counter = meterRegistry.find("catalog.product.created").tag("tenant_id", tenant1.toString())
                .counter();
        Counter tenant2Counter = meterRegistry.find("catalog.product.created").tag("tenant_id", tenant2.toString())
                .counter();

        assertNotNull(tenant1Counter, "Tenant 1 counter should be registered");
        assertNotNull(tenant2Counter, "Tenant 2 counter should be registered");

        assertEquals(2.0, tenant1Counter.count(), "Tenant 1 should have 2 products created");
        assertEquals(1.0, tenant2Counter.count(), "Tenant 2 should have 1 product created");
    }
}
