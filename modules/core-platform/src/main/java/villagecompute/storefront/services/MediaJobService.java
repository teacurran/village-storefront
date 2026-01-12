package villagecompute.storefront.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.data.models.MediaDerivative;
import villagecompute.storefront.data.repositories.MediaAssetRepository;
import villagecompute.storefront.data.repositories.MediaDerivativeRepository;
import villagecompute.storefront.data.repositories.MediaQuotaRepository;
import villagecompute.storefront.media.MediaProcessor;
import villagecompute.storefront.media.MediaStorageClient;
import villagecompute.storefront.media.MediaStoragePathBuilder;
import villagecompute.storefront.media.VideoJobHandler;
import villagecompute.storefront.services.jobs.MediaProcessingJobPayload;
import villagecompute.storefront.services.jobs.config.DeadLetterQueue;
import villagecompute.storefront.services.jobs.config.JobConfig;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.services.jobs.config.JobProcessor;
import villagecompute.storefront.services.jobs.config.PriorityJobQueue;
import villagecompute.storefront.services.jobs.config.RetryPolicy;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;

/**
 * Media job orchestration service integrating queue, processor, and media pipeline.
 *
 * <p>
 * Coordinates media processing background jobs: - Enqueues processing jobs for uploaded media assets - Processes jobs
 * by downloading from R2, generating derivatives, uploading back to R2 - Updates asset status and quota usage - Handles
 * failures with retry/DLQ
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Architecture §3.6: DelayedJob pattern with priority queues</li>
 * <li>Pattern: ReportingJobService (similar orchestration)</li>
 * </ul>
 */
@ApplicationScoped
public class MediaJobService {

    private static final Logger LOG = Logger.getLogger(MediaJobService.class);

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MediaDerivativeRepository mediaDerivativeRepository;

    @Inject
    MediaQuotaRepository mediaQuotaRepository;

    @Inject
    MediaStorageClient storageClient;

    @Inject
    MediaProcessor mediaProcessor;

    @Inject
    VideoJobHandler videoJobHandler;

    private PriorityJobQueue<MediaProcessingJobPayload> processingQueue;
    private DeadLetterQueue<MediaProcessingJobPayload> processingDlq;
    private JobProcessor<MediaProcessingJobPayload> processor;

    @PostConstruct
    void initializeJobFramework() {
        JobConfig jobConfig = buildJobConfig();

        processingQueue = new PriorityJobQueue<>("media.processing", meterRegistry, jobConfig);
        processingDlq = new DeadLetterQueue<>("media.processing", meterRegistry);
        processor = new JobProcessor<>("media.processing", meterRegistry, processingQueue, processingDlq, jobConfig,
                this::handleProcessingJob, MediaProcessingJobPayload::getTenantId);

        LOG.info("Media job framework initialized");
    }

    private JobConfig buildJobConfig() {
        return JobConfig.builder().queueCapacity(JobPriority.CRITICAL, 50).queueCapacity(JobPriority.HIGH, 200)
                .queueCapacity(JobPriority.DEFAULT, 500).queueCapacity(JobPriority.LOW, 250)
                .retryPolicy(JobPriority.CRITICAL, RetryPolicy.aggressive())
                .retryPolicy(JobPriority.HIGH, RetryPolicy.defaultPolicy())
                .retryPolicy(JobPriority.DEFAULT, RetryPolicy.defaultPolicy())
                .retryPolicy(JobPriority.LOW, RetryPolicy.defaultPolicy()).build();
    }

    /**
     * Enqueue a media processing job.
     *
     * @param mediaAssetId
     *            media asset UUID
     * @param priority
     *            job priority
     */
    public void enqueueProcessingJob(UUID mediaAssetId, JobPriority priority) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        MediaProcessingJobPayload payload = new MediaProcessingJobPayload(mediaAssetId, tenantId);

        boolean enqueued = processingQueue.enqueue(payload, priority);
        if (enqueued) {
            LOG.infof("Enqueued media processing job: assetId=%s, priority=%s", mediaAssetId, priority);
            meterRegistry
                    .counter("media.job.enqueued", "tenant", tenantId.toString(), "priority", priority.toMetricTag())
                    .increment();
        } else {
            LOG.warnf("Failed to enqueue media processing job (queue full): assetId=%s", mediaAssetId);
            meterRegistry.counter("media.job.enqueue_failed", "tenant", tenantId.toString(), "reason", "queue_full")
                    .increment();
        }
    }

    /**
     * Drain the queue synchronously (used by scheduler + tests).
     *
     * @return processed job count
     */
    public int drainQueue() {
        int processed = 0;
        while (processor.processNext()) {
            processed++;
        }
        return processed;
    }

    @Scheduled(
            every = "{media.processing.dispatch-interval:3s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "media-processing-dispatcher")
    void dispatchQueuedJobs() {
        int processed = drainQueue();
        if (processed > 0) {
            LOG.debugf("Processed %d media jobs", processed);
        }
    }

    /**
     * Handle a single media processing job.
     */
    @Transactional
    void handleProcessingJob(MediaProcessingJobPayload payload) {
        LOG.infof("Processing media job: %s", payload);

        // Fetch asset
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(payload.getMediaAssetId())
                .orElseThrow(() -> new RuntimeException("Media asset not found: " + payload.getMediaAssetId()));

        if (!"pending".equals(asset.status)) {
            LOG.warnf("Skipping media asset not in pending status: %s (status=%s)", asset.id, asset.status);
            return;
        }

        Path tempDir = null;
        try {
            // Update status to processing
            asset.status = "processing";
            mediaAssetRepository.persist(asset);

            // Download from storage to temp file
            tempDir = Files.createTempDirectory("media_processing_" + asset.id);
            String filename = asset.originalFilename != null ? asset.originalFilename : asset.id + ".bin";
            Path sourceFile = tempDir.resolve(filename);

            try (InputStream data = storageClient.downloadMedia(asset.storageKey)) {
                Files.copy(data, sourceFile);
            }

            // Process based on type
            long totalDerivativeSize = 0;
            if ("image".equals(asset.assetType)) {
                totalDerivativeSize = processImageDerivatives(asset, sourceFile, tempDir);
            } else if ("video".equals(asset.assetType)) {
                // Use VideoJobHandler for video processing (Task I4.T6)
                totalDerivativeSize = videoJobHandler.processVideo(asset, sourceFile, tempDir, payload.getTenantId());
            } else {
                throw new IllegalArgumentException("Unsupported asset type: " + asset.assetType);
            }

            // Update quota usage
            mediaQuotaRepository.updateUsage(totalDerivativeSize);

            // Update asset status
            asset.status = "ready";
            asset.processingError = null;
            mediaAssetRepository.persist(asset);

            // Cleanup temp files
            Files.walk(tempDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warnf("Failed to delete temp file: %s", p);
                }
            });

            LOG.infof("Successfully processed media asset: %s", asset.id);
            meterRegistry
                    .counter("media.job.success", "tenant", payload.getTenantId().toString(), "type", asset.assetType)
                    .increment();

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process media asset: %s", asset.id);
            asset.status = "failed";
            asset.processingError = e.getMessage();
            mediaAssetRepository.persist(asset);

            meterRegistry
                    .counter("media.job.failed", "tenant", payload.getTenantId().toString(), "type", asset.assetType)
                    .increment();
            throw new RuntimeException("Media processing failed", e);
        } finally {
            cleanupTempDir(tempDir);
        }
    }

    private long processImageDerivatives(MediaAsset asset, Path sourceFile, Path tempDir) throws IOException {
        MediaProcessor.ImageMetadata metadata = mediaProcessor.extractImageMetadata(sourceFile);
        if (metadata != null) {
            asset.width = metadata.getWidth();
            asset.height = metadata.getHeight();
        }

        List<MediaProcessor.ImageDerivative> derivatives = mediaProcessor.processImage(sourceFile, tempDir);
        long totalSize = 0;

        for (MediaProcessor.ImageDerivative derivative : derivatives) {
            String derivativeKey = buildDerivativeKey(asset, derivative.getType(),
                    derivative.getFilePath().getFileName().toString());

            // Upload derivative to storage
            try (InputStream data = Files.newInputStream(derivative.getFilePath())) {
                storageClient.uploadMedia(derivativeKey, data, derivative.getMimeType(), derivative.getFileSize());
            }

            // Save derivative metadata
            MediaDerivative derivativeEntity = new MediaDerivative();
            derivativeEntity.asset = asset;
            derivativeEntity.tenant = asset.tenant;
            derivativeEntity.derivativeType = derivative.getType();
            derivativeEntity.storageKey = derivativeKey;
            derivativeEntity.mimeType = derivative.getMimeType();
            derivativeEntity.fileSize = derivative.getFileSize();
            derivativeEntity.width = derivative.getWidth();
            derivativeEntity.height = derivative.getHeight();
            mediaDerivativeRepository.persist(derivativeEntity);

            totalSize += derivative.getFileSize();
            LOG.infof("Uploaded image derivative: type=%s, size=%d", derivative.getType(), derivative.getFileSize());
        }

        return totalSize;
    }

    // Video processing now handled by VideoJobHandler (Task I4.T6)

    private String buildDerivativeKey(MediaAsset asset, String derivativeType, String filename) {
        return MediaStoragePathBuilder.buildDerivativeKey(asset.tenant.id, asset.assetType, asset.id, derivativeType,
                filename);
    }

    private void cleanupTempDir(Path tempDir) {
        if (tempDir == null) {
            return;
        }
        try {
            Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.warnf(e, "Failed to delete temp file: %s", p);
                }
            });
        } catch (IOException e) {
            LOG.warnf(e, "Unable to clean temp directory %s", tempDir);
        }
    }
}
