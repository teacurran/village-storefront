package villagecompute.storefront.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.opencsv.CSVWriter;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.data.models.InventoryAgingAggregate;
import villagecompute.storefront.data.models.ReportJob;
import villagecompute.storefront.data.models.SalesByPeriodAggregate;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.ConsignmentPayoutAggregateRepository;
import villagecompute.storefront.data.repositories.InventoryAgingAggregateRepository;
import villagecompute.storefront.data.repositories.ReportJobRepository;
import villagecompute.storefront.data.repositories.SalesByPeriodAggregateRepository;
import villagecompute.storefront.reporting.ReportStorageClient;
import villagecompute.storefront.services.jobs.ReportExportJobPayload;
import villagecompute.storefront.services.jobs.ReportRefreshJobPayload;
import villagecompute.storefront.services.jobs.config.DeadLetterQueue;
import villagecompute.storefront.services.jobs.config.JobConfig;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.services.jobs.config.JobProcessor;
import villagecompute.storefront.services.jobs.config.PriorityJobQueue;
import villagecompute.storefront.services.jobs.config.RetryPolicy;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Service for managing report generation jobs and exports.
 *
 * <p>
 * Provides job queue management, export generation, and orchestration of async report workflows. Integrates with
 * ReportStorageClient for R2 upload and signed URL generation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@ApplicationScoped
public class ReportingJobService {

    private static final Logger LOG = Logger.getLogger(ReportingJobService.class);
    private static final Duration DEFAULT_SIGNED_URL_EXPIRY = Duration.ofHours(24);

    @Inject
    ReportJobRepository reportJobRepository;

    @Inject
    SalesByPeriodAggregateRepository salesAggregateRepo;

    @Inject
    ConsignmentPayoutAggregateRepository payoutAggregateRepo;

    @Inject
    InventoryAgingAggregateRepository agingAggregateRepo;

    @Inject
    ReportingProjectionService projectionService;

    @Inject
    ReportStorageClient storageClient;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(
            name = "jobs.queue.capacity.critical",
            defaultValue = "1000")
    int criticalQueueCapacity;

    @ConfigProperty(
            name = "jobs.queue.capacity.high",
            defaultValue = "5000")
    int highQueueCapacity;

    @ConfigProperty(
            name = "jobs.queue.capacity.default",
            defaultValue = "10000")
    int defaultQueueCapacity;

    @ConfigProperty(
            name = "jobs.queue.capacity.low",
            defaultValue = "10000")
    int lowQueueCapacity;

    @ConfigProperty(
            name = "jobs.queue.capacity.bulk",
            defaultValue = "20000")
    int bulkQueueCapacity;

    @ConfigProperty(
            name = "jobs.retry.max_attempts.critical",
            defaultValue = "5")
    int criticalRetryAttempts;

    @ConfigProperty(
            name = "jobs.retry.max_attempts.high",
            defaultValue = "3")
    int highRetryAttempts;

    @ConfigProperty(
            name = "jobs.retry.max_attempts.default",
            defaultValue = "3")
    int defaultRetryAttempts;

    @ConfigProperty(
            name = "jobs.retry.max_attempts.low",
            defaultValue = "3")
    int lowRetryAttempts;

    private JobConfig jobConfig;
    private PriorityJobQueue<ReportRefreshJobPayload> refreshQueue;
    private DeadLetterQueue<ReportRefreshJobPayload> refreshDlq;
    private JobProcessor<ReportRefreshJobPayload> refreshProcessor;

    private PriorityJobQueue<ReportExportJobPayload> exportQueue;
    private DeadLetterQueue<ReportExportJobPayload> exportDlq;
    private JobProcessor<ReportExportJobPayload> exportProcessor;

    @PostConstruct
    void initializeJobFramework() {
        jobConfig = buildJobConfig();

        refreshQueue = new PriorityJobQueue<>("reporting.refresh", meterRegistry, jobConfig);
        refreshDlq = new DeadLetterQueue<>("reporting.refresh", meterRegistry);
        refreshProcessor = new JobProcessor<>("reporting.refresh", meterRegistry, refreshQueue, refreshDlq, jobConfig,
                this::handleRefreshJob, ReportRefreshJobPayload::getTenantId);

        exportQueue = new PriorityJobQueue<>("reporting.export", meterRegistry, jobConfig);
        exportDlq = new DeadLetterQueue<>("reporting.export", meterRegistry);
        exportProcessor = new JobProcessor<>("reporting.export", meterRegistry, exportQueue, exportDlq, jobConfig,
                this::handleExportJob, ReportExportJobPayload::getTenantId);
    }

    /**
     * Enqueue a refresh job for aggregate computation.
     *
     * @param aggregateType
     *            type of aggregate (sales_by_period, consignment_payout, inventory_aging)
     * @param periodStart
     *            start date for time-based aggregates
     * @param periodEnd
     *            end date for time-based aggregates
     * @return job ID
     */
    public UUID enqueueRefresh(String aggregateType, LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        ReportRefreshJobPayload payload = ReportRefreshJobPayload.create(tenantId, aggregateType, periodStart,
                periodEnd);

        JobPriority priority = priorityForRefresh(payload.getAggregateType());
        boolean enqueued = refreshQueue.enqueue(payload, priority);
        if (!enqueued) {
            meterRegistry.counter("reporting.job.enqueue_rejected", "type", "refresh", "aggregate_type", aggregateType)
                    .increment();
            throw new IllegalStateException("Refresh queue capacity reached for priority " + priority);
        }

        LOG.infof("Enqueued refresh job - jobId=%s, tenantId=%s, aggregateType=%s", payload.getJobId(), tenantId,
                aggregateType);

        meterRegistry.counter("reporting.job.enqueued", "type", "refresh", "aggregate_type", aggregateType).increment();

        return payload.getJobId();
    }

    /**
     * Enqueue an export job for report generation.
     *
     * @param reportType
     *            type of report
     * @param format
     *            export format (csv, pdf, xlsx)
     * @param parameters
     *            report-specific parameters
     * @param requestedBy
     *            user who requested the export
     * @return report job ID
     */
    @Transactional
    public UUID enqueueExport(String reportType, String format, Map<String, String> parameters, String requestedBy) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Create ReportJob entity
        ReportJob reportJob = new ReportJob();
        reportJob.tenant = Tenant.findById(tenantId);
        reportJob.reportType = reportType;
        reportJob.status = "pending";
        reportJob.requestedBy = requestedBy;
        reportJob.parameters = parameters != null ? parameters.toString() : "{}";
        reportJob.createdAt = OffsetDateTime.now();
        reportJob.updatedAt = OffsetDateTime.now();

        reportJobRepository.persist(reportJob);

        // Enqueue export job payload
        ReportExportJobPayload payload = ReportExportJobPayload.create(tenantId, reportJob.id, reportType, format,
                parameters, requestedBy);

        JobPriority priority = priorityForExport(reportType);
        boolean enqueued = exportQueue.enqueue(payload, priority);
        if (!enqueued) {
            meterRegistry.counter("reporting.job.enqueue_rejected", "type", "export", "report_type", reportType)
                    .increment();
            throw new IllegalStateException("Export queue capacity reached for priority " + priority);
        }

        LOG.infof("Enqueued export job - jobId=%s, reportJobId=%s, tenantId=%s, reportType=%s, format=%s",
                payload.getJobId(), reportJob.id, tenantId, reportType, format);

        meterRegistry.counter("reporting.job.enqueued", "type", "export", "report_type", reportType).increment();

        return reportJob.id;
    }

    /**
     * Process next refresh job from the queue.
     *
     * @return true if a job was processed
     */
    @Transactional
    public boolean processNextRefreshJob() {
        return refreshProcessor.processNext();
    }

    /**
     * Process next export job from the queue.
     *
     * @return true if a job was processed
     */
    @Transactional
    public boolean processNextExportJob() {
        return exportProcessor.processNext();
    }

    /**
     * Generate report data based on payload parameters.
     *
     * @param payload
     *            export job payload
     * @return report data as byte array
     */
    private byte[] generateReportData(ReportExportJobPayload payload) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                CSVWriter csvWriter = new CSVWriter(writer)) {

            switch (payload.getReportType()) {
                case "sales_by_period" :
                    generateSalesReport(csvWriter, payload.getParameters());
                    break;
                case "consignment_payout" :
                    generatePayoutReport(csvWriter, payload.getParameters());
                    break;
                case "inventory_aging" :
                    generateAgingReport(csvWriter, payload.getParameters());
                    break;
                default :
                    throw new IllegalArgumentException("Unknown report type: " + payload.getReportType());
            }

            csvWriter.flush();
            return outputStream.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to generate report data", e);
        }
    }

    private void generateSalesReport(CSVWriter csvWriter, Map<String, String> parameters) {
        csvWriter.writeNext(new String[]{"Period Start", "Period End", "Total Amount", "Item Count", "Order Count",
                "Data Freshness"});

        List<SalesByPeriodAggregate> aggregates = salesAggregateRepo.findByCurrentTenant();
        for (SalesByPeriodAggregate agg : aggregates) {
            csvWriter.writeNext(new String[]{agg.periodStart.toString(), agg.periodEnd.toString(),
                    agg.totalAmount.toString(), String.valueOf(agg.itemCount), String.valueOf(agg.orderCount),
                    agg.dataFreshnessTimestamp.toString()});
        }
    }

    private void generatePayoutReport(CSVWriter csvWriter, Map<String, String> parameters) {
        csvWriter.writeNext(new String[]{"Consignor ID", "Period Start", "Period End", "Total Owed", "Item Count",
                "Items Sold", "Data Freshness"});

        List<ConsignmentPayoutAggregate> aggregates = payoutAggregateRepo.findByCurrentTenant();
        for (ConsignmentPayoutAggregate agg : aggregates) {
            csvWriter.writeNext(new String[]{agg.consignor.id.toString(), agg.periodStart.toString(),
                    agg.periodEnd.toString(), agg.totalOwed.toString(), String.valueOf(agg.itemCount),
                    String.valueOf(agg.itemsSold), agg.dataFreshnessTimestamp.toString()});
        }
    }

    private void generateAgingReport(CSVWriter csvWriter, Map<String, String> parameters) {
        csvWriter.writeNext(new String[]{"Variant SKU", "Location", "Days In Stock", "Quantity", "First Received",
                "Data Freshness"});

        List<InventoryAgingAggregate> aggregates = agingAggregateRepo.findByCurrentTenant();
        for (InventoryAgingAggregate agg : aggregates) {
            String sku = agg.variant != null ? agg.variant.sku : "N/A";
            String locationName = agg.location != null ? agg.location.name : "N/A";
            String firstReceived = agg.firstReceivedAt != null ? agg.firstReceivedAt.toString() : "N/A";

            csvWriter.writeNext(new String[]{sku, locationName, String.valueOf(agg.daysInStock),
                    String.valueOf(agg.quantity), firstReceived, agg.dataFreshnessTimestamp.toString()});
        }
    }

    /**
     * Get refresh queue depth (for testing/monitoring).
     *
     * @return queue depth
     */
    public int getRefreshQueueDepth() {
        return refreshQueue != null ? refreshQueue.getTotalDepth() : 0;
    }

    /**
     * Get export queue depth (for testing/monitoring).
     *
     * @return queue depth
     */
    public int getExportQueueDepth() {
        return exportQueue != null ? exportQueue.getTotalDepth() : 0;
    }

    private JobConfig buildJobConfig() {
        RetryPolicy criticalPolicy = RetryPolicy.builder().maxAttempts(criticalRetryAttempts)
                .initialDelay(Duration.ofMillis(500)).maxDelay(Duration.ofSeconds(30)).backoffMultiplier(1.5)
                .exponentialBackoff(true).build();

        RetryPolicy highPolicy = RetryPolicy.builder().maxAttempts(highRetryAttempts)
                .initialDelay(Duration.ofSeconds(1)).maxDelay(Duration.ofMinutes(5)).backoffMultiplier(2.0)
                .exponentialBackoff(true).build();

        RetryPolicy defaultPolicy = RetryPolicy.builder().maxAttempts(defaultRetryAttempts)
                .initialDelay(Duration.ofSeconds(1)).maxDelay(Duration.ofMinutes(5)).backoffMultiplier(2.0)
                .exponentialBackoff(true).build();

        RetryPolicy lowPolicy = RetryPolicy.builder().maxAttempts(lowRetryAttempts).initialDelay(Duration.ofSeconds(2))
                .maxDelay(Duration.ofMinutes(10)).backoffMultiplier(2.0).exponentialBackoff(true).build();

        return JobConfig.builder().retryPolicy(JobPriority.CRITICAL, criticalPolicy)
                .retryPolicy(JobPriority.HIGH, highPolicy).retryPolicy(JobPriority.DEFAULT, defaultPolicy)
                .retryPolicy(JobPriority.LOW, lowPolicy).retryPolicy(JobPriority.BULK, RetryPolicy.noRetry())
                .queueCapacity(JobPriority.CRITICAL, criticalQueueCapacity)
                .queueCapacity(JobPriority.HIGH, highQueueCapacity)
                .queueCapacity(JobPriority.DEFAULT, defaultQueueCapacity)
                .queueCapacity(JobPriority.LOW, lowQueueCapacity).queueCapacity(JobPriority.BULK, bulkQueueCapacity)
                .build();
    }

    private JobPriority priorityForRefresh(String aggregateType) {
        if ("consignment_payout".equals(aggregateType)) {
            return JobPriority.HIGH;
        }
        if ("inventory_aging".equals(aggregateType)) {
            return JobPriority.LOW;
        }
        return JobPriority.DEFAULT;
    }

    private JobPriority priorityForExport(String reportType) {
        if ("consignment_payout".equals(reportType)) {
            return JobPriority.HIGH;
        }
        if ("inventory_aging".equals(reportType)) {
            return JobPriority.LOW;
        }
        return JobPriority.DEFAULT;
    }

    private void handleRefreshJob(ReportRefreshJobPayload payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        LOG.infof("Processing refresh job - jobId=%s, tenantId=%s, aggregateType=%s", payload.getJobId(),
                payload.getTenantId(), payload.getAggregateType());

        meterRegistry.counter("reporting.job.started", "type", "refresh", "aggregate_type", payload.getAggregateType())
                .increment();

        try {
            switch (payload.getAggregateType()) {
                case "sales_by_period" :
                    projectionService.refreshSalesAggregates(payload.getPeriodStart(), payload.getPeriodEnd());
                    break;
                case "consignment_payout" :
                    projectionService.refreshConsignmentPayoutAggregates(payload.getPeriodStart(),
                            payload.getPeriodEnd());
                    break;
                case "inventory_aging" :
                    projectionService.refreshInventoryAgingAggregates();
                    break;
                default :
                    LOG.warnf("Unknown aggregate type: %s", payload.getAggregateType());
            }

            LOG.infof("Refresh job completed - jobId=%s, aggregateType=%s", payload.getJobId(),
                    payload.getAggregateType());

            sample.stop(meterRegistry.timer("reporting.job.duration", "type", "refresh", "aggregate_type",
                    payload.getAggregateType()));
        } catch (Exception e) {
            LOG.errorf(e, "Refresh job failed - jobId=%s, aggregateType=%s", payload.getJobId(),
                    payload.getAggregateType());
            meterRegistry
                    .counter("reporting.job.failed", "type", "refresh", "aggregate_type", payload.getAggregateType())
                    .increment();
            throw e;
        }
    }

    private void handleExportJob(ReportExportJobPayload payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        LOG.infof("Processing export job - jobId=%s, reportJobId=%s, tenantId=%s, reportType=%s", payload.getJobId(),
                payload.getReportJobId(), payload.getTenantId(), payload.getReportType());

        meterRegistry.counter("reporting.job.started", "type", "export", "report_type", payload.getReportType())
                .increment();

        ReportJob reportJob = ReportJob.findById(payload.getReportJobId());
        if (reportJob == null) {
            throw new IllegalStateException("ReportJob not found: " + payload.getReportJobId());
        }

        reportJob.status = "running";
        reportJob.startedAt = OffsetDateTime.now();
        reportJob.updatedAt = OffsetDateTime.now();
        reportJob.persist();

        try {
            byte[] reportData = generateReportData(payload);
            String objectKey = String.format("%s/%s/%s.%s", payload.getTenantId(), payload.getReportType(),
                    payload.getJobId(), payload.getFormat());

            String contentType = "text/csv"; // MVP: CSV only
            storageClient.uploadReport(objectKey, new ByteArrayInputStream(reportData), contentType, reportData.length);

            String signedUrl = storageClient.getSignedDownloadUrl(objectKey, DEFAULT_SIGNED_URL_EXPIRY);

            reportJob.status = "completed";
            reportJob.resultUrl = signedUrl;
            reportJob.completedAt = OffsetDateTime.now();
            reportJob.updatedAt = OffsetDateTime.now();
            reportJob.persist();

            LOG.infof("Export job completed - jobId=%s, reportJobId=%s, resultUrl=%s", payload.getJobId(),
                    payload.getReportJobId(), signedUrl);

            sample.stop(meterRegistry.timer("reporting.job.duration", "type", "export", "report_type",
                    payload.getReportType()));
        } catch (Exception e) {
            LOG.errorf(e, "Export job failed - jobId=%s, reportJobId=%s", payload.getJobId(), payload.getReportJobId());

            reportJob.status = "failed";
            reportJob.errorMessage = e.getMessage();
            reportJob.completedAt = OffsetDateTime.now();
            reportJob.updatedAt = OffsetDateTime.now();
            reportJob.persist();

            meterRegistry.counter("reporting.job.failed", "type", "export", "report_type", payload.getReportType())
                    .increment();
            throw e;
        }
    }
}
