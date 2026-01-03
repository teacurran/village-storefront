package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.*;
import villagecompute.storefront.data.repositories.ReportJobRepository;
import villagecompute.storefront.data.repositories.SalesByPeriodAggregateRepository;
import villagecompute.storefront.reporting.StubReportStorageClient;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for report export workflow.
 *
 * <p>
 * Verifies end-to-end export job execution, R2 upload (stubbed), and signed URL generation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Testing Guidance: Use test double for R2, ensure scheduled jobs tested</li>
 * </ul>
 */
@QuarkusTest
public class ReportExportIT {

    @Inject
    ReportingJobService reportingJobService;

    @Inject
    ReportingProjectionService projectionService;

    @Inject
    ReportJobRepository reportJobRepository;

    @Inject
    SalesByPeriodAggregateRepository salesAggregateRepo;

    @Inject
    StubReportStorageClient stubStorageClient;

    private Tenant testTenant;

    @BeforeEach
    @Transactional
    public void setup() {
        // Clear stub storage
        stubStorageClient.clear();

        // Create test tenant
        testTenant = new Tenant();
        testTenant.subdomain = "test-export-" + UUID.randomUUID().toString().substring(0, 8);
        testTenant.name = "Test Export Tenant";
        testTenant.status = "active";
        testTenant.createdAt = OffsetDateTime.now();
        testTenant.updatedAt = OffsetDateTime.now();
        testTenant.persist();

        TenantContext.setCurrentTenantId(testTenant.id);
    }

    @AfterEach
    public void cleanup() {
        stubStorageClient.clear();
        TenantContext.clear();
    }

    @Test
    @Transactional
    public void testExportJob_FullWorkflow() {
        // Setup: Create sales aggregate
        LocalDate today = LocalDate.now();
        projectionService.refreshSalesAggregates(today, today);

        Optional<SalesByPeriodAggregate> aggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertTrue(aggregateOpt.isPresent(), "Sales aggregate should exist");

        // Enqueue export job
        Map<String, String> parameters = new HashMap<>();
        parameters.put("startDate", today.toString());
        parameters.put("endDate", today.toString());

        UUID jobId = reportingJobService.enqueueExport("sales_by_period", "csv", parameters, "test-user");
        assertNotNull(jobId);

        // Verify job was created
        Optional<ReportJob> jobOpt = reportJobRepository.findByIdOptional(jobId);
        assertTrue(jobOpt.isPresent());
        assertEquals("pending", jobOpt.get().status);

        // Process export job
        boolean processed = reportingJobService.processNextExportJob();
        assertTrue(processed, "Export job should be processed");

        // Verify job completion
        ReportJob completedJob = ReportJob.findById(jobId);
        assertNotNull(completedJob);
        assertEquals("completed", completedJob.status);
        assertNotNull(completedJob.startedAt);
        assertNotNull(completedJob.completedAt);
        assertNotNull(completedJob.resultUrl);
        assertTrue(completedJob.resultUrl.contains("stub-storage.example.com"));

        // Verify export was uploaded to stub storage
        assertTrue(stubStorageClient.reportExists(completedJob.resultUrl.split("\\?")[0].split("/reports/")[1]));
    }

    @Test
    @Transactional
    public void testExportJob_CsvContent() {
        // Setup: Create sales aggregate with known data
        SalesByPeriodAggregate aggregate = new SalesByPeriodAggregate();
        aggregate.tenant = testTenant;
        aggregate.periodStart = LocalDate.now();
        aggregate.periodEnd = LocalDate.now();
        aggregate.totalAmount = new BigDecimal("1234.56");
        aggregate.itemCount = 10;
        aggregate.orderCount = 3;
        aggregate.dataFreshnessTimestamp = OffsetDateTime.now();
        aggregate.jobName = "test-job";
        aggregate.createdAt = OffsetDateTime.now();
        aggregate.updatedAt = OffsetDateTime.now();
        aggregate.persist();

        // Enqueue and process export
        UUID jobId = reportingJobService.enqueueExport("sales_by_period", "csv", Map.of(), "test-user");
        reportingJobService.processNextExportJob();

        // Retrieve stored report
        ReportJob job = ReportJob.findById(jobId);
        String objectKey = job.resultUrl.split("\\?")[0].split("/reports/")[1];

        StubReportStorageClient.StoredReport report = stubStorageClient.getStoredReport(objectKey);
        assertNotNull(report);
        assertEquals("text/csv", report.getContentType());

        // Verify CSV content
        String csvContent = new String(report.getData());
        assertTrue(csvContent.contains("Period Start"));
        assertTrue(csvContent.contains("Total Amount"));
        assertTrue(csvContent.contains("1234.56"));
        assertTrue(csvContent.contains(aggregate.periodStart.toString()));
    }

    @Test
    @Transactional
    public void testExportJob_TenantIsolation() {
        // Create second tenant
        Tenant tenant2 = new Tenant();
        tenant2.subdomain = "test-export-2-" + UUID.randomUUID().toString().substring(0, 8);
        tenant2.name = "Test Export Tenant 2";
        tenant2.status = "active";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = OffsetDateTime.now();
        tenant2.persist();

        // Create job for tenant 1
        TenantContext.setCurrentTenantId(testTenant.id);
        UUID job1Id = reportingJobService.enqueueExport("sales_by_period", "csv", Map.of(), "user1");

        // Switch to tenant 2
        TenantContext.setCurrentTenantId(tenant2.id);

        // Verify tenant 2 cannot see tenant 1's job
        Optional<ReportJob> job1Opt = reportJobRepository.findByIdOptional(job1Id);
        if (job1Opt.isPresent()) {
            // Job exists but should not be in tenant 2's list
            assertEquals(testTenant.id, job1Opt.get().tenant.id);
            assertEquals(0, reportJobRepository.findByCurrentTenant(0, 10).size(), "Tenant 2 should have no jobs");
        }
    }

    @Test
    @Transactional
    public void testExportJob_ErrorHandling() {
        // Enqueue job with invalid report type
        UUID jobId = reportingJobService.enqueueExport("invalid_report_type", "csv", Map.of(), "test-user");

        // Process should fail gracefully
        try {
            reportingJobService.processNextExportJob();
        } catch (Exception e) {
            // Expected - invalid report type
        }

        // Verify job was marked as failed
        ReportJob job = ReportJob.findById(jobId);
        assertNotNull(job);
        assertEquals("failed", job.status);
        assertNotNull(job.errorMessage);
        assertNotNull(job.completedAt);
    }

    @Test
    @Transactional
    public void testMultipleExportJobs() {
        // Enqueue multiple jobs
        UUID job1Id = reportingJobService.enqueueExport("sales_by_period", "csv", Map.of(), "user1");
        UUID job2Id = reportingJobService.enqueueExport("inventory_aging", "csv", Map.of(), "user2");

        assertEquals(2, reportingJobService.getExportQueueDepth());

        // Process both jobs
        reportingJobService.processNextExportJob();
        reportingJobService.processNextExportJob();

        assertEquals(0, reportingJobService.getExportQueueDepth());

        // Verify both jobs completed
        ReportJob job1 = ReportJob.findById(job1Id);
        ReportJob job2 = ReportJob.findById(job2Id);

        assertNotNull(job1);
        assertNotNull(job2);
        assertEquals("completed", job1.status);
        assertEquals("completed", job2.status);
    }

    @Test
    @Transactional
    public void testRefreshJobQueue() {
        LocalDate today = LocalDate.now();

        // Enqueue refresh job
        UUID jobId = reportingJobService.enqueueRefresh("sales_by_period", today, today);
        assertNotNull(jobId);

        assertEquals(1, reportingJobService.getRefreshQueueDepth());

        // Process refresh job
        boolean processed = reportingJobService.processNextRefreshJob();
        assertTrue(processed);

        assertEquals(0, reportingJobService.getRefreshQueueDepth());

        // Verify aggregate was created
        Optional<SalesByPeriodAggregate> aggregateOpt = salesAggregateRepo.findByExactPeriod(today, today);
        assertTrue(aggregateOpt.isPresent());
    }
}
