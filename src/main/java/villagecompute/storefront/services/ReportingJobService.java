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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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

    // In-memory queues for MVP (similar to BarcodeLabelJobQueue pattern)
    private final Queue<ReportRefreshJobPayload> refreshQueue = new ConcurrentLinkedQueue<>();
    private final Queue<ReportExportJobPayload> exportQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger refreshQueueDepth = new AtomicInteger(0);
    private final AtomicInteger exportQueueDepth = new AtomicInteger(0);

    @PostConstruct
    void registerQueueGauges() {
        if (meterRegistry != null) {
            meterRegistry.gauge("reporting.refresh.queue.depth", refreshQueueDepth, AtomicInteger::get);
            meterRegistry.gauge("reporting.export.queue.depth", exportQueueDepth, AtomicInteger::get);
        }
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

        refreshQueue.add(payload);
        refreshQueueDepth.incrementAndGet();

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

        exportQueue.add(payload);
        exportQueueDepth.incrementAndGet();

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
        ReportRefreshJobPayload payload = refreshQueue.poll();
        if (payload == null) {
            return false;
        }

        refreshQueueDepth.decrementAndGet();

        UUID previousTenantId = TenantContext.hasContext() ? TenantContext.getCurrentTenantId() : null;
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Restore tenant context from payload
            TenantContext.setCurrentTenantId(payload.getTenantId());

            LOG.infof("Processing refresh job - jobId=%s, tenantId=%s, aggregateType=%s", payload.getJobId(),
                    payload.getTenantId(), payload.getAggregateType());

            meterRegistry
                    .counter("reporting.job.started", "type", "refresh", "aggregate_type", payload.getAggregateType())
                    .increment();

            // Execute refresh based on aggregate type
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

            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Refresh job failed - jobId=%s, aggregateType=%s", payload.getJobId(),
                    payload.getAggregateType());
            meterRegistry
                    .counter("reporting.job.failed", "type", "refresh", "aggregate_type", payload.getAggregateType())
                    .increment();
            throw e;
        } finally {
            // Restore previous tenant context
            if (previousTenantId != null) {
                TenantContext.setCurrentTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * Process next export job from the queue.
     *
     * @return true if a job was processed
     */
    @Transactional
    public boolean processNextExportJob() {
        ReportExportJobPayload payload = exportQueue.poll();
        if (payload == null) {
            return false;
        }

        exportQueueDepth.decrementAndGet();

        UUID previousTenantId = TenantContext.hasContext() ? TenantContext.getCurrentTenantId() : null;
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Restore tenant context from payload
            TenantContext.setCurrentTenantId(payload.getTenantId());

            LOG.infof("Processing export job - jobId=%s, reportJobId=%s, tenantId=%s, reportType=%s",
                    payload.getJobId(), payload.getReportJobId(), payload.getTenantId(), payload.getReportType());

            // Update job status to running
            ReportJob reportJob = ReportJob.findById(payload.getReportJobId());
            if (reportJob == null) {
                LOG.errorf("ReportJob not found: %s", payload.getReportJobId());
                return false;
            }

            reportJob.status = "running";
            reportJob.startedAt = OffsetDateTime.now();
            reportJob.updatedAt = OffsetDateTime.now();
            reportJob.persist();

            meterRegistry.counter("reporting.job.started", "type", "export", "report_type", payload.getReportType())
                    .increment();

            // Generate report based on type
            byte[] reportData = generateReportData(payload);

            // Upload to R2
            String objectKey = String.format("%s/%s/%s.%s", payload.getTenantId(), payload.getReportType(),
                    payload.getJobId(), payload.getFormat());

            String contentType = "text/csv"; // MVP: CSV only
            storageClient.uploadReport(objectKey, new ByteArrayInputStream(reportData), contentType, reportData.length);

            // Generate signed URL
            String signedUrl = storageClient.getSignedDownloadUrl(objectKey, DEFAULT_SIGNED_URL_EXPIRY);

            // Update job with result
            reportJob.status = "completed";
            reportJob.resultUrl = signedUrl;
            reportJob.completedAt = OffsetDateTime.now();
            reportJob.updatedAt = OffsetDateTime.now();
            reportJob.persist();

            LOG.infof("Export job completed - jobId=%s, reportJobId=%s, resultUrl=%s", payload.getJobId(),
                    payload.getReportJobId(), signedUrl);

            sample.stop(meterRegistry.timer("reporting.job.duration", "type", "export", "report_type",
                    payload.getReportType()));

            return true;

        } catch (Exception e) {
            LOG.errorf(e, "Export job failed - jobId=%s, reportJobId=%s", payload.getJobId(), payload.getReportJobId());

            // Mark job as failed
            ReportJob reportJob = ReportJob.findById(payload.getReportJobId());
            if (reportJob != null) {
                reportJob.status = "failed";
                reportJob.errorMessage = e.getMessage();
                reportJob.completedAt = OffsetDateTime.now();
                reportJob.updatedAt = OffsetDateTime.now();
                reportJob.persist();
            }

            meterRegistry.counter("reporting.job.failed", "type", "export", "report_type", payload.getReportType())
                    .increment();

            throw e;
        } finally {
            // Restore previous tenant context
            if (previousTenantId != null) {
                TenantContext.setCurrentTenantId(previousTenantId);
            } else {
                TenantContext.clear();
            }
        }
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
        return refreshQueueDepth.get();
    }

    /**
     * Get export queue depth (for testing/monitoring).
     *
     * @return queue depth
     */
    public int getExportQueueDepth() {
        return exportQueueDepth.get();
    }
}
