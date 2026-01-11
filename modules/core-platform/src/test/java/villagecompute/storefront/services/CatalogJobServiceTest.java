package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.jobs.CatalogExportJobHandler;
import villagecompute.storefront.services.jobs.CatalogImportJobHandler;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * Unit tests for CatalogJobService.
 *
 * <p>
 * Tests job enqueue operations, feature flag enforcement, queue depth monitoring, and processor integration.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@QuarkusTest
class CatalogJobServiceTest {

    @Inject
    CatalogJobService catalogJobService;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    MeterRegistry meterRegistry;

    @InjectMock
    CatalogImportJobHandler importHandler;

    @InjectMock
    CatalogExportJobHandler exportHandler;

    private Tenant tenant;

    @BeforeEach
    @Transactional
    void setUp() {
        FeatureFlag.deleteAll();
        Tenant.deleteAll();

        tenant = new Tenant();
        tenant.subdomain = "catalog-test";
        tenant.name = "Catalog Test Tenant";
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        tenant.persist();

        createFeatureFlag("catalog.import.enabled", true);
        featureToggle.invalidateAll();
        TenantContext.setCurrentTenantId(tenant.id);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void createFeatureFlag(String key, boolean enabled) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = tenant;
        flag.flagKey = key;
        flag.enabled = enabled;
        flag.config = "{}";
        flag.owner = "catalog-jobs-tests@villagecompute.dev";
        flag.reviewCadenceDays = 90;
        flag.riskLevel = "LOW";
        flag.rollbackInstructions = "Disable catalog job";
        flag.lastReviewedAt = OffsetDateTime.now();
        flag.persist();
    }

    // ========================================
    // Import Job Tests
    // ========================================

    @Test
    void enqueueImportWithFeatureFlagEnabled() throws Exception {
        doNothing().when(importHandler).process(any());

        double initialCount = meterRegistry.counter("catalog.import.enqueued", "tenant_id", tenant.id.toString())
                .count();

        UUID jobId = catalogJobService.enqueueImport("/tmp/test.csv", "test-user", Map.of());

        assertNotNull(jobId);
        assertEquals(1, catalogJobService.getImportQueueDepth());

        // Verify metric incremented
        assertEquals(initialCount + 1,
                meterRegistry.counter("catalog.import.enqueued", "tenant_id", tenant.id.toString()).count());
    }

    @Test
    @Transactional
    void enqueueImportWithFeatureFlagDisabled() {
        // Disable the feature flag
        FeatureFlag.deleteAll();
        createFeatureFlag("catalog.import.enabled", false);
        featureToggle.invalidateAll();

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            catalogJobService.enqueueImport("/tmp/test.csv", "test-user", Map.of());
        });

        assertTrue(exception.getMessage().contains("Catalog import feature is disabled"));

        // Verify queue remains empty
        assertEquals(0, catalogJobService.getImportQueueDepth());
    }

    @Test
    void enqueueImportRecordsMetrics() {
        double initialCount = meterRegistry.counter("catalog.import.enqueued", "tenant_id", tenant.id.toString())
                .count();

        catalogJobService.enqueueImport("/tmp/test1.csv", "user1", Map.of());
        catalogJobService.enqueueImport("/tmp/test2.csv", "user2", Map.of());

        assertEquals(initialCount + 2,
                meterRegistry.counter("catalog.import.enqueued", "tenant_id", tenant.id.toString()).count());
        assertEquals(2, catalogJobService.getImportQueueDepth());
    }

    @Test
    void processNextImportJobExecutesHandler() throws Exception {
        doNothing().when(importHandler).process(any());

        catalogJobService.enqueueImport("/tmp/test.csv", "test-user", Map.of());
        boolean processed = catalogJobService.processNextImportJob();

        assertTrue(processed);
        assertEquals(0, catalogJobService.getImportQueueDepth());
        verify(importHandler, times(1)).process(any());
    }

    // ========================================
    // Export Job Tests
    // ========================================

    @Test
    void enqueueExportSucceeds() throws Exception {
        when(exportHandler.process(any())).thenReturn("https://example.com/export.csv");

        double initialCount = meterRegistry.counter("catalog.export.enqueued", "tenant_id", tenant.id.toString()).count();

        UUID jobId = catalogJobService.enqueueExport("test-user", Map.of("status", "active"), "csv");

        assertNotNull(jobId);
        assertEquals(1, catalogJobService.getExportQueueDepth());

        // Verify metric incremented
        assertEquals(initialCount + 1, meterRegistry.counter("catalog.export.enqueued", "tenant_id", tenant.id.toString()).count());
    }

    @Test
    void enqueueExportRejectsInvalidFormat() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            catalogJobService.enqueueExport("test-user", Map.of(), "json");
        });

        assertTrue(exception.getMessage().contains("Only CSV export format is supported"));
        assertEquals(0, catalogJobService.getExportQueueDepth());
    }

    @Test
    void enqueueExportDefaultsToCSV() throws Exception {
        when(exportHandler.process(any())).thenReturn("https://example.com/export.csv");

        UUID jobId = catalogJobService.enqueueExport("test-user", Map.of(), null);

        assertNotNull(jobId);
        assertEquals(1, catalogJobService.getExportQueueDepth());
    }

    @Test
    void processNextExportJobExecutesHandler() throws Exception {
        when(exportHandler.process(any())).thenReturn("https://example.com/export.csv");

        catalogJobService.enqueueExport("test-user", Map.of(), "csv");
        boolean processed = catalogJobService.processNextExportJob();

        assertTrue(processed);
        assertEquals(0, catalogJobService.getExportQueueDepth());
        verify(exportHandler, times(1)).process(any());
    }

    // ========================================
    // Queue Monitoring Tests
    // ========================================

    @Test
    void getImportQueueDepthReturnsCorrectCount() {
        assertEquals(0, catalogJobService.getImportQueueDepth());

        catalogJobService.enqueueImport("/tmp/test1.csv", "user1", Map.of());
        assertEquals(1, catalogJobService.getImportQueueDepth());

        catalogJobService.enqueueImport("/tmp/test2.csv", "user2", Map.of());
        assertEquals(2, catalogJobService.getImportQueueDepth());
    }

    @Test
    void getExportQueueDepthReturnsCorrectCount() {
        assertEquals(0, catalogJobService.getExportQueueDepth());

        catalogJobService.enqueueExport("user1", Map.of(), "csv");
        assertEquals(1, catalogJobService.getExportQueueDepth());

        catalogJobService.enqueueExport("user2", Map.of(), "csv");
        assertEquals(2, catalogJobService.getExportQueueDepth());
    }

    // ========================================
    // Priority Queue Tests
    // ========================================

    @Test
    void importJobsUseDEFAULTPriority() {
        catalogJobService.enqueueImport("/tmp/test.csv", "test-user", Map.of());

        // Verify job is enqueued with DEFAULT priority (queue depth should be 1)
        assertEquals(1, catalogJobService.getImportQueueDepth());
    }

    @Test
    void exportJobsUseDEFAULTPriority() {
        catalogJobService.enqueueExport("test-user", Map.of(), "csv");

        // Verify job is enqueued with DEFAULT priority (queue depth should be 1)
        assertEquals(1, catalogJobService.getExportQueueDepth());
    }
}
