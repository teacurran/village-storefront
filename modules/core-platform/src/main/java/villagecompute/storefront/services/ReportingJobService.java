package villagecompute.storefront.services;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.opencsv.CSVWriter;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.data.models.InventoryAgingAggregate;
import villagecompute.storefront.data.models.LoyaltyAggregate;
import villagecompute.storefront.data.models.ReportJob;
import villagecompute.storefront.data.models.SalesByPeriodAggregate;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.ConsignmentPayoutAggregateRepository;
import villagecompute.storefront.data.repositories.InventoryAgingAggregateRepository;
import villagecompute.storefront.data.repositories.LoyaltyAggregateRepository;
import villagecompute.storefront.data.repositories.ReportJobRepository;
import villagecompute.storefront.data.repositories.SalesByPeriodAggregateRepository;
import villagecompute.storefront.reporting.ConsignmentAggregateView;
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
    LoyaltyAggregateRepository loyaltyAggregateRepo;

    @Inject
    ReportingProjectionService projectionService;

    @Inject
    ReportStorageClient storageClient;

    @Inject
    ConsignmentAggregateView consignmentAggregateView;

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
            name = "reporting.exports.hot_retention_days",
            defaultValue = "90")
    int hotStorageRetentionDays;

    @ConfigProperty(
            name = "reporting.exports.default_range_days",
            defaultValue = "30")
    int defaultRangeDays;

    @ConfigProperty(
            name = "reporting.exports.max_range_days",
            defaultValue = "365")
    int maxRangeDays;

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
        String normalizedFormat = normalizeFormat(format);
        Map<String, String> normalizedParams = normalizeParameters(parameters);

        // Create ReportJob entity
        ReportJob reportJob = new ReportJob();
        reportJob.tenant = Tenant.findById(tenantId);
        reportJob.reportType = reportType;
        reportJob.status = "pending";
        reportJob.requestedBy = requestedBy;
        reportJob.parameters = toJsonString(normalizedParams);
        reportJob.createdAt = OffsetDateTime.now();
        reportJob.updatedAt = OffsetDateTime.now();

        reportJobRepository.persist(reportJob);

        // Enqueue export job payload
        ReportExportJobPayload payload = ReportExportJobPayload.create(tenantId, reportJob.id, reportType,
                normalizedFormat, normalizedParams, requestedBy);

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

    private ReportGenerationResult generateReport(ReportExportJobPayload payload) {
        Map<String, String> parameters = payload.getParameters() != null ? payload.getParameters()
                : Collections.emptyMap();
        DateRange dateRange = null;
        ReportDataset dataset;

        switch (payload.getReportType()) {
            case "sales_by_period" :
                dateRange = resolveDateRange(parameters);
                dataset = buildSalesDataset(dateRange);
                break;
            case "consignment_payout" :
                dateRange = resolveDateRange(parameters);
                dataset = buildConsignmentDataset(dateRange);
                break;
            case "inventory_aging" :
                dataset = buildInventoryDataset();
                break;
            case "loyalty_summary" :
                dateRange = resolveDateRange(parameters);
                dataset = buildLoyaltyDataset(dateRange);
                break;
            default :
                throw new IllegalArgumentException("Unknown report type: " + payload.getReportType());
        }

        byte[] data = formatDataset(dataset, payload.getFormat());
        return new ReportGenerationResult(data, dateRange);
    }

    private ReportDataset buildSalesDataset(DateRange dateRange) {
        List<SalesByPeriodAggregate> aggregates = dateRange != null && dateRange.hasHotWindow()
                ? salesAggregateRepo.findByPeriodRange(dateRange.hotQueryStart(), dateRange.hotQueryEnd())
                : Collections.emptyList();

        List<Map<String, Object>> rows = new ArrayList<>(aggregates.size());
        for (SalesByPeriodAggregate aggregate : aggregates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodStart", aggregate.periodStart);
            row.put("periodEnd", aggregate.periodEnd);
            row.put("totalAmount", aggregate.totalAmount);
            row.put("itemCount", aggregate.itemCount);
            row.put("orderCount", aggregate.orderCount);
            row.put("dataFreshnessTimestamp", aggregate.dataFreshnessTimestamp);
            rows.add(row);
        }

        List<String> headers = List.of("periodStart", "periodEnd", "totalAmount", "itemCount", "orderCount",
                "dataFreshnessTimestamp");
        return new ReportDataset(headers, rows);
    }

    private ReportDataset buildConsignmentDataset(DateRange dateRange) {
        List<ConsignmentPayoutAggregate> aggregates = dateRange != null && dateRange.hasHotWindow()
                ? consignmentAggregateView.queryByDateRange(dateRange.hotQueryStart(), dateRange.hotQueryEnd())
                : Collections.emptyList();

        List<Map<String, Object>> rows = new ArrayList<>(aggregates.size());
        for (ConsignmentPayoutAggregate aggregate : aggregates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("consignorId", aggregate.consignor.id);
            row.put("periodStart", aggregate.periodStart);
            row.put("periodEnd", aggregate.periodEnd);
            row.put("totalOwed", aggregate.totalOwed);
            row.put("itemCount", aggregate.itemCount);
            row.put("itemsSold", aggregate.itemsSold);
            row.put("dataFreshnessTimestamp", aggregate.dataFreshnessTimestamp);
            rows.add(row);
        }

        List<String> headers = List.of("consignorId", "periodStart", "periodEnd", "totalOwed", "itemCount", "itemsSold",
                "dataFreshnessTimestamp");
        return new ReportDataset(headers, rows);
    }

    private ReportDataset buildInventoryDataset() {
        List<InventoryAgingAggregate> aggregates = agingAggregateRepo.findByCurrentTenant();
        List<Map<String, Object>> rows = new ArrayList<>(aggregates.size());

        for (InventoryAgingAggregate aggregate : aggregates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("variantSku", aggregate.variant != null ? aggregate.variant.sku : null);
            row.put("variantId", aggregate.variant != null ? aggregate.variant.id : null);
            row.put("locationId", aggregate.location != null ? aggregate.location.id : null);
            row.put("locationName", aggregate.location != null ? aggregate.location.name : null);
            row.put("daysInStock", aggregate.daysInStock);
            row.put("quantity", aggregate.quantity);
            row.put("firstReceivedAt", aggregate.firstReceivedAt);
            row.put("dataFreshnessTimestamp", aggregate.dataFreshnessTimestamp);
            rows.add(row);
        }

        List<String> headers = List.of("variantSku", "variantId", "locationId", "locationName", "daysInStock",
                "quantity", "firstReceivedAt", "dataFreshnessTimestamp");
        return new ReportDataset(headers, rows);
    }

    private ReportDataset buildLoyaltyDataset(DateRange dateRange) {
        List<LoyaltyAggregate> aggregates = dateRange != null && dateRange.hasHotWindow()
                ? loyaltyAggregateRepo.findByCurrentTenantAndPeriod(dateRange.hotQueryStart(), dateRange.hotQueryEnd())
                : Collections.emptyList();

        List<Map<String, Object>> rows = new ArrayList<>(aggregates.size());
        for (LoyaltyAggregate aggregate : aggregates) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodDate", aggregate.periodDate);
            row.put("pointsEarned", aggregate.pointsEarned);
            row.put("pointsRedeemed", aggregate.pointsRedeemed);
            row.put("activeMembers", aggregate.activeMembers);
            row.put("tierDistribution", aggregate.tierDistribution);
            row.put("dataFreshnessTimestamp", aggregate.dataFreshnessTimestamp);
            rows.add(row);
        }

        List<String> headers = List.of("periodDate", "pointsEarned", "pointsRedeemed", "activeMembers",
                "tierDistribution", "dataFreshnessTimestamp");
        return new ReportDataset(headers, rows);
    }

    private byte[] formatDataset(ReportDataset dataset, String format) {
        String normalizedFormat = format != null ? format.toLowerCase(Locale.ROOT) : "csv";
        if ("json".equals(normalizedFormat)) {
            JsonArrayBuilder arrayBuilder = Json.createArrayBuilder();
            for (Map<String, Object> row : dataset.rows()) {
                JsonObjectBuilder rowBuilder = Json.createObjectBuilder();
                row.forEach((key, value) -> addJsonValue(rowBuilder, key, value));
                arrayBuilder.add(rowBuilder);
            }
            return arrayBuilder.build().toString().getBytes(StandardCharsets.UTF_8);
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                CSVWriter csvWriter = new CSVWriter(writer)) {

            csvWriter.writeNext(dataset.headers().toArray(new String[0]));
            for (Map<String, Object> row : dataset.rows()) {
                String[] values = new String[dataset.headers().size()];
                for (int i = 0; i < dataset.headers().size(); i++) {
                    Object value = row.get(dataset.headers().get(i));
                    values[i] = value != null ? value.toString() : "";
                }
                csvWriter.writeNext(values);
            }
            csvWriter.flush();
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate report data", e);
        }
    }

    private void addJsonValue(JsonObjectBuilder builder, String key, Object value) {
        if (value == null) {
            builder.addNull(key);
            return;
        }

        if (value instanceof Integer intValue) {
            builder.add(key, intValue);
        } else if (value instanceof Long longValue) {
            builder.add(key, longValue);
        } else if (value instanceof Double doubleValue) {
            builder.add(key, doubleValue);
        } else if (value instanceof Float floatValue) {
            builder.add(key, floatValue.doubleValue());
        } else if (value instanceof Boolean boolValue) {
            builder.add(key, boolValue);
        } else {
            builder.add(key, value.toString());
        }
    }

    private DateRange resolveDateRange(Map<String, String> parameters) {
        LocalDate today = LocalDate.now();
        LocalDate requestedEnd = parseDate("endDate", parameters.get("endDate"));
        if (requestedEnd == null) {
            requestedEnd = today;
        }
        if (requestedEnd.isAfter(today)) {
            requestedEnd = today;
        }

        int normalizedDefaultRange = Math.max(defaultRangeDays, 1);
        LocalDate requestedStart = parseDate("startDate", parameters.get("startDate"));
        if (requestedStart == null) {
            requestedStart = requestedEnd.minusDays(normalizedDefaultRange - 1L);
        }

        int normalizedMaxRange = Math.max(maxRangeDays, normalizedDefaultRange);
        long rangeDays = ChronoUnit.DAYS.between(requestedStart, requestedEnd);
        if (rangeDays > normalizedMaxRange) {
            requestedStart = requestedEnd.minusDays(normalizedMaxRange);
        }

        if (requestedStart.isAfter(requestedEnd)) {
            throw new IllegalArgumentException("startDate must be on or before endDate");
        }

        LocalDate hotCutoff = LocalDate.now().minusDays(hotStorageRetentionDays);
        boolean includesArchived = requestedStart.isBefore(hotCutoff);
        boolean archiveOnly = requestedEnd.isBefore(hotCutoff);

        LocalDate hotQueryStart = archiveOnly ? null
                : (requestedStart.isBefore(hotCutoff) ? hotCutoff : requestedStart);
        LocalDate hotQueryEnd = archiveOnly ? null : requestedEnd;

        LocalDate archivedStart = includesArchived ? requestedStart : null;
        LocalDate archivedEnd = includesArchived
                ? (requestedEnd.isBefore(hotCutoff) ? requestedEnd : hotCutoff.minusDays(1))
                : null;

        return new DateRange(requestedStart, requestedEnd, hotQueryStart, hotQueryEnd, archivedStart, archivedEnd,
                includesArchived, archiveOnly);
    }

    private LocalDate parseDate(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid " + fieldName + ": " + value, e);
        }
    }

    private String contentTypeForFormat(String format) {
        if (format != null && "json".equalsIgnoreCase(format)) {
            return "application/json";
        }
        return "text/csv";
    }

    private String normalizeFormat(String format) {
        String normalized = format != null ? format.trim().toLowerCase(Locale.ROOT) : "csv";
        if (!"csv".equals(normalized) && !"json".equals(normalized)) {
            throw new IllegalArgumentException("Unsupported export format: " + format);
        }
        return normalized;
    }

    private Map<String, String> normalizeParameters(Map<String, String> parameters) {
        Map<String, String> normalized = new HashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (key != null && value != null) {
                    normalized.put(key.trim(), value.trim());
                }
            });
        }
        return normalized;
    }

    private String toJsonString(Map<String, String> parameters) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        parameters.forEach(builder::add);
        return builder.build().toString();
    }

    private String resolveDataSource(DateRange dateRange) {
        if (dateRange == null) {
            return "hot_only";
        }
        if (dateRange.archiveOnly()) {
            return "archived_only";
        }
        if (dateRange.includesArchived()) {
            return "mixed";
        }
        return "hot_only";
    }

    private record ReportDataset(List<String> headers, List<Map<String, Object>> rows) {
    }

    private record ReportGenerationResult(byte[] data, DateRange dateRange) {
    }

    private record DateRange(LocalDate requestedStart, LocalDate requestedEnd, LocalDate hotQueryStart,
            LocalDate hotQueryEnd, LocalDate archivedStart, LocalDate archivedEnd, boolean includesArchived,
            boolean archiveOnly) {

        boolean hasHotWindow() {
            return hotQueryStart != null && hotQueryEnd != null && !hotQueryStart.isAfter(hotQueryEnd);
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
        OffsetDateTime queuedAt = payload.getCreatedAt();
        OffsetDateTime processingStartedAt = OffsetDateTime.now();

        // Record queue wait time
        long queueWaitMillis = java.time.Duration.between(queuedAt, processingStartedAt).toMillis();
        meterRegistry.timer("reporting.job.queue_time", "type", "export", "report_type", payload.getReportType())
                .record(java.time.Duration.ofMillis(queueWaitMillis));

        LOG.infof("Processing export job - jobId=%s, reportJobId=%s, tenantId=%s, reportType=%s, queueWaitMs=%d",
                payload.getJobId(), payload.getReportJobId(), payload.getTenantId(), payload.getReportType(),
                queueWaitMillis);

        meterRegistry.counter("reporting.job.started", "type", "export", "report_type", payload.getReportType())
                .increment();

        ReportJob reportJob = ReportJob.findById(payload.getReportJobId());
        if (reportJob == null) {
            throw new IllegalStateException("ReportJob not found: " + payload.getReportJobId());
        }

        // Check if job was cancelled
        if (reportJob.cancelled) {
            LOG.infof("Export job cancelled - jobId=%s, reportJobId=%s", payload.getJobId(), payload.getReportJobId());
            meterRegistry.counter("reporting.job.cancelled", "type", "export", "report_type", payload.getReportType())
                    .increment();
            sample.stop(meterRegistry.timer("reporting.job.duration", "type", "export", "report_type",
                    payload.getReportType()));
            return;
        }

        reportJob.status = "running";
        reportJob.startedAt = processingStartedAt;
        reportJob.updatedAt = processingStartedAt;
        reportJob.persist();

        try {
            ReportGenerationResult generationResult = generateReport(payload);
            byte[] reportData = generationResult.data();
            String objectKey = String.format("%s/%s/%s.%s", payload.getTenantId(), payload.getReportType(),
                    payload.getJobId(), payload.getFormat());

            String contentType = contentTypeForFormat(payload.getFormat());
            storageClient.uploadReport(objectKey, new ByteArrayInputStream(reportData), contentType, reportData.length);

            String signedUrl = storageClient.getSignedDownloadUrl(objectKey, DEFAULT_SIGNED_URL_EXPIRY);

            // Build manifest metadata
            OffsetDateTime urlExpiresAt = OffsetDateTime.now().plus(DEFAULT_SIGNED_URL_EXPIRY);
            String manifestMetadata = buildManifestMetadata(payload, reportData.length, generationResult.dateRange(),
                    urlExpiresAt);

            reportJob.status = "completed";
            reportJob.resultUrl = signedUrl;
            reportJob.urlExpiresAt = urlExpiresAt;
            reportJob.manifestMetadata = manifestMetadata;
            reportJob.completedAt = OffsetDateTime.now();
            reportJob.updatedAt = OffsetDateTime.now();
            reportJob.persist();

            LOG.infof("Export job completed - jobId=%s, reportJobId=%s, resultUrl=%s, fileSizeBytes=%d",
                    payload.getJobId(), payload.getReportJobId(), signedUrl, reportData.length);

            // Record file size metric
            meterRegistry.summary("reports.export.file_size_bytes", "report_type", payload.getReportType())
                    .record(reportData.length);

            sample.stop(meterRegistry.timer("reporting.job.duration", "type", "export", "report_type",
                    payload.getReportType()));
            meterRegistry.counter("reporting.job.completed", "type", "export", "report_type", payload.getReportType())
                    .increment();
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

    /**
     * Build manifest metadata JSON for archived/hot data ranges.
     *
     * @param payload
     *            export job payload
     * @param fileSizeBytes
     *            generated file size
     * @return JSON manifest metadata
     */
    private String buildManifestMetadata(ReportExportJobPayload payload, long fileSizeBytes, DateRange dateRange,
            OffsetDateTime urlExpiresAt) {
        JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("tenantId", payload.getTenantId().toString());
        builder.add("reportType", payload.getReportType());
        builder.add("format", payload.getFormat());
        builder.add("fileSizeBytes", fileSizeBytes);
        builder.add("generatedAt", OffsetDateTime.now().toString());
        builder.add("retentionPolicyDays", hotStorageRetentionDays);
        builder.add("partitionAware", true);
        builder.add("dataSource", resolveDataSource(dateRange));

        if (urlExpiresAt != null) {
            builder.add("downloadExpiresAt", urlExpiresAt.toString());
        }

        if (dateRange != null && dateRange.requestedStart() != null && dateRange.requestedEnd() != null) {
            JsonObjectBuilder requested = Json.createObjectBuilder()
                    .add("startDate", dateRange.requestedStart().toString())
                    .add("endDate", dateRange.requestedEnd().toString());
            builder.add("requestedRange", requested);

            if (dateRange.hasHotWindow()) {
                JsonObjectBuilder hotRange = Json.createObjectBuilder()
                        .add("startDate", dateRange.hotQueryStart().toString())
                        .add("endDate", dateRange.hotQueryEnd().toString());
                builder.add("hotStorageRange", hotRange);
            }

            builder.add("archivalLookupRequired", dateRange.includesArchived());
            builder.add("archiveOnly", dateRange.archiveOnly());

            if (dateRange.includesArchived() && dateRange.archivedStart() != null && dateRange.archivedEnd() != null) {
                JsonObjectBuilder archivedRange = Json.createObjectBuilder()
                        .add("startDate", dateRange.archivedStart().toString())
                        .add("endDate", dateRange.archivedEnd().toString());
                builder.add("archivedRange", archivedRange);
            }
        } else {
            builder.add("archivalLookupRequired", false);
            builder.add("archiveOnly", false);
        }

        return builder.build().toString();
    }
}
