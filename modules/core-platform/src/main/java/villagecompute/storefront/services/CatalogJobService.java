package villagecompute.storefront.services;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.services.jobs.CatalogExportJobHandler;
import villagecompute.storefront.services.jobs.CatalogExportJobPayload;
import villagecompute.storefront.services.jobs.CatalogImportJobHandler;
import villagecompute.storefront.services.jobs.CatalogImportJobPayload;
import villagecompute.storefront.services.jobs.config.DeadLetterQueue;
import villagecompute.storefront.services.jobs.config.JobConfig;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.services.jobs.config.JobProcessor;
import villagecompute.storefront.services.jobs.config.PriorityJobQueue;
import villagecompute.storefront.services.jobs.config.RetryPolicy;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for managing catalog import/export background jobs.
 *
 * <p>
 * Provides job queue management, enqueue operations, and orchestration of async catalog import/export workflows.
 * Integrates with DelayedJob framework using DEFAULT priority queues.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogJobService {

    private static final Logger LOG = Logger.getLogger(CatalogJobService.class);

    @Inject
    CatalogImportJobHandler importHandler;

    @Inject
    CatalogExportJobHandler exportHandler;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    FeatureToggle featureToggle;

    @ConfigProperty(
            name = "jobs.queue.capacity.default",
            defaultValue = "10000")
    int defaultQueueCapacity;

    @ConfigProperty(
            name = "jobs.retry.max_attempts.default",
            defaultValue = "3")
    int defaultRetryAttempts;

    private JobConfig jobConfig;
    private PriorityJobQueue<CatalogImportJobPayload> importQueue;
    private DeadLetterQueue<CatalogImportJobPayload> importDlq;
    private JobProcessor<CatalogImportJobPayload> importProcessor;

    private PriorityJobQueue<CatalogExportJobPayload> exportQueue;
    private DeadLetterQueue<CatalogExportJobPayload> exportDlq;
    private JobProcessor<CatalogExportJobPayload> exportProcessor;

    @PostConstruct
    void initializeJobFramework() {
        jobConfig = buildJobConfig();

        importQueue = new PriorityJobQueue<>("catalog.import", meterRegistry, jobConfig);
        importDlq = new DeadLetterQueue<>("catalog.import", meterRegistry);
        importProcessor = new JobProcessor<>("catalog.import", meterRegistry, importQueue, importDlq, jobConfig,
                this::handleImportJob, CatalogImportJobPayload::getTenantId);

        exportQueue = new PriorityJobQueue<>("catalog.export", meterRegistry, jobConfig);
        exportDlq = new DeadLetterQueue<>("catalog.export", meterRegistry);
        exportProcessor = new JobProcessor<>("catalog.export", meterRegistry, exportQueue, exportDlq, jobConfig,
                this::handleExportJob, CatalogExportJobPayload::getTenantId);
    }

    /**
     * Enqueue a catalog import job.
     *
     * @param fileLocation
     *            path to CSV file
     * @param requestedBy
     *            user who requested the import
     * @param options
     *            import options
     * @return job ID
     * @throws IllegalStateException
     *             if feature flag is disabled or queue is full
     */
    public UUID enqueueImport(String fileLocation, String requestedBy, Map<String, String> options) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Check feature flag
        if (!featureToggle.isEnabled("catalog.import.enabled")) {
            throw new IllegalStateException("Catalog import feature is disabled");
        }

        CatalogImportJobPayload payload = CatalogImportJobPayload.create(tenantId, fileLocation, requestedBy, options);

        boolean enqueued = importQueue.enqueue(payload, JobPriority.DEFAULT);
        if (!enqueued) {
            meterRegistry.counter("catalog.import.enqueue_rejected", "tenant_id", tenantId.toString()).increment();
            throw new IllegalStateException("Import queue capacity reached");
        }

        LOG.infof("Enqueued catalog import job - jobId=%s, tenantId=%s, file=%s", payload.getJobId(), tenantId,
                fileLocation);

        meterRegistry.counter("catalog.import.enqueued", "tenant_id", tenantId.toString()).increment();

        return payload.getJobId();
    }

    /**
     * Enqueue a catalog export job.
     *
     * @param requestedBy
     *            user who requested the export
     * @param filters
     *            export filters (e.g., status)
     * @param format
     *            export format (csv)
     * @return job ID
     * @throws IllegalStateException
     *             if queue is full
     */
    public UUID enqueueExport(String requestedBy, Map<String, String> filters, String format) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String normalizedFormat = (format == null || format.isBlank()) ? "csv" : format.toLowerCase();
        if (!"csv".equals(normalizedFormat)) {
            throw new IllegalArgumentException("Only CSV export format is supported");
        }

        CatalogExportJobPayload payload = CatalogExportJobPayload.create(tenantId, requestedBy, filters,
                normalizedFormat);

        boolean enqueued = exportQueue.enqueue(payload, JobPriority.DEFAULT);
        if (!enqueued) {
            meterRegistry.counter("catalog.export.enqueue_rejected", "tenant_id", tenantId.toString()).increment();
            throw new IllegalStateException("Export queue capacity reached");
        }

        LOG.infof("Enqueued catalog export job - jobId=%s, tenantId=%s, format=%s", payload.getJobId(), tenantId,
                format);

        meterRegistry.counter("catalog.export.enqueued", "tenant_id", tenantId.toString()).increment();

        return payload.getJobId();
    }

    /**
     * Process next import job from the queue.
     *
     * @return true if a job was processed
     */
    @Transactional
    public boolean processNextImportJob() {
        return importProcessor.processNext();
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
     * Get import queue depth (for monitoring).
     *
     * @return queue depth
     */
    public int getImportQueueDepth() {
        return importQueue != null ? importQueue.getTotalDepth() : 0;
    }

    /**
     * Get export queue depth (for monitoring).
     *
     * @return queue depth
     */
    public int getExportQueueDepth() {
        return exportQueue != null ? exportQueue.getTotalDepth() : 0;
    }

    private JobConfig buildJobConfig() {
        RetryPolicy defaultPolicy = RetryPolicy.builder().maxAttempts(defaultRetryAttempts)
                .initialDelay(Duration.ofSeconds(1)).maxDelay(Duration.ofMinutes(5)).backoffMultiplier(2.0)
                .exponentialBackoff(true).build();

        return JobConfig.builder().retryPolicy(JobPriority.DEFAULT, defaultPolicy)
                .queueCapacity(JobPriority.DEFAULT, defaultQueueCapacity).build();
    }

    private void handleImportJob(CatalogImportJobPayload payload) throws Exception {
        LOG.infof("Processing import job - jobId=%s, tenantId=%s", payload.getJobId(), payload.getTenantId());
        importHandler.process(payload);
    }

    private void handleExportJob(CatalogExportJobPayload payload) throws Exception {
        LOG.infof("Processing export job - jobId=%s, tenantId=%s", payload.getJobId(), payload.getTenantId());
        exportHandler.process(payload);
    }
}
