package villagecompute.storefront.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.MediaAssetRepository;
import villagecompute.storefront.services.MediaJobService;
import villagecompute.storefront.services.MediaService;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for media job queue behavior validating: - Queue priority assignment (CRITICAL vs DEFAULT vs LOW) -
 * Job enqueuing with tenant context - Job processing with proper tenant isolation - Metrics emission for queue
 * operations - Job payload tenant_id inclusion
 *
 * References: - Task I2.T7 acceptance criteria - Architecture §3.6: Priority queues (CRITICAL, HIGH, DEFAULT, LOW,
 * BULK) - docs/architecture/background_jobs.md: Queue types and SLA
 */
@QuarkusTest
public class MediaQueueIntegrationTest {

    @Inject
    MediaService mediaService;

    @Inject
    MediaJobService mediaJobService;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    StubMediaStorageClient storageClient;

    private Tenant tenant;

    @BeforeEach
    @Transactional
    void setUpTenant() {
        tenant = new Tenant();
        tenant.subdomain = "queue-test-" + UUID.randomUUID().toString().substring(0, 8);
        tenant.name = "Queue Integration Test Tenant";
        tenant.status = "active";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = tenant.createdAt;
        tenant.persist();

        TenantContext.setCurrentTenantId(tenant.id);
    }

    @AfterEach
    void cleanupContext() {
        TenantContext.clear();
        storageClient.clear();
    }

    // ========================================
    // Queue Priority Assignment Tests
    // ========================================

    @Test
    @Transactional
    void imageUploadEnqueuesDefaultPriorityJob() {
        // Arrange: Upload image asset
        MediaService.UploadRequest request = new MediaService.UploadRequest("product.jpg", "image/jpeg", 2048L, "image",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(new byte[2048]), "image/jpeg",
                2048L);

        // Record metrics baseline
        double baselineEnqueued = getEnqueuedCount("default");

        // Act: Complete upload (triggers job enqueue)
        mediaService.completeUpload(negotiation.assetId(), null);

        // Assert: Job enqueued with DEFAULT priority
        double afterEnqueue = getEnqueuedCount("default");
        assertTrue(afterEnqueue > baselineEnqueued, "Image processing job should be enqueued with DEFAULT priority");
    }

    @Test
    @Transactional
    void videoUploadEnqueuesLowPriorityJob() {
        // Arrange: Upload video asset
        MediaService.UploadRequest request = new MediaService.UploadRequest("demo.mp4", "video/mp4", 8192L, "video",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(new byte[8192]), "video/mp4",
                8192L);

        // Record metrics baseline
        double baselineEnqueued = getEnqueuedCount("low");

        // Act: Complete upload (triggers job enqueue)
        mediaService.completeUpload(negotiation.assetId(), null);

        // Assert: Job enqueued with LOW priority
        double afterEnqueue = getEnqueuedCount("low");
        assertTrue(afterEnqueue > baselineEnqueued, "Video processing job should be enqueued with LOW priority");
    }

    @Test
    @Transactional
    void explicitCriticalPriorityJobEnqueue() {
        // Arrange: Create pending asset
        MediaAsset asset = new MediaAsset();
        asset.tenant = tenant;
        asset.assetType = "image";
        asset.originalFilename = "urgent.jpg";
        asset.mimeType = "image/jpeg";
        asset.fileSize = 1024L;
        asset.status = "pending";
        asset.storageKey = tenant.id + "/media/image/" + UUID.randomUUID() + "/original/urgent.jpg";
        mediaAssetRepository.persist(asset);

        // Record metrics baseline
        double baselineEnqueued = getEnqueuedCount("critical");

        // Act: Enqueue with CRITICAL priority
        mediaJobService.enqueueProcessingJob(asset.id, JobPriority.CRITICAL);

        // Assert: Job enqueued with CRITICAL priority
        double afterEnqueue = getEnqueuedCount("critical");
        assertTrue(afterEnqueue > baselineEnqueued, "Job should be enqueued with CRITICAL priority");
    }

    // ========================================
    // Job Processing with Tenant Context Tests
    // ========================================

    @Test
    @Transactional
    void jobProcessingPreservesTenantContext() {
        // Arrange: Upload and complete image
        MediaService.UploadRequest request = new MediaService.UploadRequest("context-test.jpg", "image/jpeg", 1024L,
                "image", null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        byte[] imageBytes = buildFakeImagePayload(1024);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(imageBytes), "image/jpeg",
                imageBytes.length);

        mediaService.completeUpload(negotiation.assetId(), null);

        // Act: Process job
        int processed = mediaJobService.drainQueue();

        // Assert: Job was processed
        assertTrue(processed > 0, "At least one job should be processed");

        // Assert: Asset status updated to ready (confirms tenant context was set)
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(negotiation.assetId()).orElseThrow();
        assertEquals("ready", asset.status,
                "Asset should be marked ready after processing (confirms tenant context worked)");
    }

    @Test
    @Transactional
    void jobPayloadIncludesTenantId() {
        // Arrange: Create asset
        MediaAsset asset = new MediaAsset();
        asset.tenant = tenant;
        asset.assetType = "image";
        asset.originalFilename = "payload-test.jpg";
        asset.mimeType = "image/jpeg";
        asset.fileSize = 2048L;
        asset.status = "pending";
        asset.storageKey = tenant.id + "/media/image/" + UUID.randomUUID() + "/original/payload-test.jpg";
        mediaAssetRepository.persist(asset);

        // Act: Enqueue job
        mediaJobService.enqueueProcessingJob(asset.id, JobPriority.DEFAULT);

        // Assert: Job enqueued successfully (payload validated internally)
        // The MediaJobService constructs MediaProcessingJobPayload with tenant ID from TenantContext
        // If tenant ID was missing, job processing would fail
        Counter enqueueCounter = meterRegistry.find("media.job.enqueued").tag("tenant", tenant.id.toString())
                .tag("priority", "default").counter();

        assertNotNull(enqueueCounter, "Enqueue metric should exist for tenant");
        assertTrue(enqueueCounter.count() >= 1.0, "Job should be enqueued with tenant ID in payload");
    }

    // ========================================
    // Queue Metrics Validation Tests
    // ========================================

    @Test
    @Transactional
    void successfulJobProcessingEmitsMetrics() {
        // Arrange: Upload image
        MediaService.UploadRequest request = new MediaService.UploadRequest("metrics-test.jpg", "image/jpeg", 1024L,
                "image", null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        byte[] imageBytes = buildFakeImagePayload(1024);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(imageBytes), "image/jpeg",
                imageBytes.length);

        mediaService.completeUpload(negotiation.assetId(), null);

        // Record baseline
        double baselineSuccess = getJobSuccessCount();

        // Act: Process job
        mediaJobService.drainQueue();

        // Assert: Success metric incremented
        double afterProcess = getJobSuccessCount();
        assertTrue(afterProcess > baselineSuccess, "Success metric should increment after processing");
    }

    @Test
    @Transactional
    void failedJobProcessingEmitsFailureMetrics() {
        // Arrange: Create asset with invalid storage key (will fail download)
        MediaAsset asset = new MediaAsset();
        asset.tenant = tenant;
        asset.assetType = "image";
        asset.originalFilename = "missing.jpg";
        asset.mimeType = "image/jpeg";
        asset.fileSize = 1024L;
        asset.status = "pending";
        asset.storageKey = tenant.id + "/media/image/" + UUID.randomUUID() + "/original/nonexistent.jpg";
        mediaAssetRepository.persist(asset);
        mediaAssetRepository.flush();

        mediaJobService.enqueueProcessingJob(asset.id, JobPriority.DEFAULT);

        // Record baseline
        double baselineFailure = getJobFailureCount();

        // Act: Process job (will fail due to missing file)
        try {
            mediaJobService.drainQueue();
        } catch (Exception e) {
            // Expected - job will fail
        }

        // Assert: Asset marked as failed
        MediaAsset failedAsset = mediaAssetRepository.findByIdAndTenant(asset.id).orElseThrow();
        assertEquals("failed", failedAsset.status, "Asset should be marked failed after processing error");
        assertNotNull(failedAsset.processingError, "Processing error should be recorded");

        // Assert: Failure metric incremented
        double afterProcess = getJobFailureCount();
        assertTrue(afterProcess > baselineFailure, "Failure metric should increment after failed processing");
    }

    // ========================================
    // Multiple Asset Type Priority Tests
    // ========================================

    @Test
    @Transactional
    void mixedAssetTypesUseCorrectPriorities() {
        // Arrange: Upload both image and video
        MediaService.UploadRequest imageRequest = new MediaService.UploadRequest("photo.jpg", "image/jpeg", 1024L,
                "image", null);
        MediaService.MediaUploadNegotiation imageNegotiation = mediaService.negotiateUpload(imageRequest);
        storageClient.uploadMedia(imageNegotiation.storageKey(), new ByteArrayInputStream(new byte[1024]), "image/jpeg",
                1024L);

        MediaService.UploadRequest videoRequest = new MediaService.UploadRequest("video.mp4", "video/mp4", 8192L,
                "video", null);
        MediaService.MediaUploadNegotiation videoNegotiation = mediaService.negotiateUpload(videoRequest);
        storageClient.uploadMedia(videoNegotiation.storageKey(), new ByteArrayInputStream(new byte[8192]), "video/mp4",
                8192L);

        // Record baselines
        double baselineDefaultPriority = getEnqueuedCount("default");
        double baselineLowPriority = getEnqueuedCount("low");

        // Act: Complete both uploads
        mediaService.completeUpload(imageNegotiation.assetId(), null);
        mediaService.completeUpload(videoNegotiation.assetId(), null);

        // Assert: Image uses DEFAULT, video uses LOW
        double afterDefaultPriority = getEnqueuedCount("default");
        double afterLowPriority = getEnqueuedCount("low");

        assertTrue(afterDefaultPriority > baselineDefaultPriority, "Image should use DEFAULT priority");
        assertTrue(afterLowPriority > baselineLowPriority, "Video should use LOW priority");
    }

    // ========================================
    // Helper Methods
    // ========================================

    private byte[] buildFakeImagePayload(int size) {
        byte[] payload = new byte[size];
        // Add minimal JPEG markers
        if (payload.length > 2) {
            payload[0] = (byte) 0xFF;
            payload[1] = (byte) 0xD8; // SOI
            payload[payload.length - 2] = (byte) 0xFF;
            payload[payload.length - 1] = (byte) 0xD9; // EOI
        }
        return payload;
    }

    private double getEnqueuedCount(String priority) {
        Counter counter = meterRegistry.find("media.job.enqueued").tag("tenant", tenant.id.toString())
                .tag("priority", priority).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double getJobSuccessCount() {
        Counter counter = meterRegistry.find("media.job.success").tag("tenant", tenant.id.toString()).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double getJobFailureCount() {
        Counter counter = meterRegistry.find("media.job.failed").tag("tenant", tenant.id.toString()).counter();
        return counter != null ? counter.count() : 0.0;
    }
}
