package villagecompute.storefront.media;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.data.models.MediaDerivative;
import villagecompute.storefront.data.models.MediaQuota;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.MediaAccessLogRepository;
import villagecompute.storefront.data.repositories.MediaAssetRepository;
import villagecompute.storefront.data.repositories.MediaDerivativeRepository;
import villagecompute.storefront.data.repositories.MediaQuotaRepository;
import villagecompute.storefront.media.exceptions.MediaQuotaExceededException;
import villagecompute.storefront.services.MediaJobService;
import villagecompute.storefront.services.MediaService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for the media pipeline using stubbed processors/storage.
 */
@QuarkusTest
public class MediaPipelineTest {

    @Inject
    MediaService mediaService;

    @Inject
    MediaJobService mediaJobService;

    @Inject
    StubMediaStorageClient storageClient;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MediaDerivativeRepository mediaDerivativeRepository;

    @Inject
    MediaQuotaRepository mediaQuotaRepository;

    @Inject
    MediaAccessLogRepository mediaAccessLogRepository;

    private Tenant tenant;

    @BeforeEach
    @Transactional
    void setUpTenant() {
        tenant = new Tenant();
        tenant.subdomain = "media-test-" + UUID.randomUUID().toString().substring(0, 8);
        tenant.name = "Media Pipeline Test";
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

    @Test
    @Transactional
    void imagePipelineProcessesDerivativesAndSignedUrls() {
        MediaService.UploadRequest request = new MediaService.UploadRequest("product.jpg", "image/jpeg", 4096L, "image",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        byte[] imageBytes = buildFakeMediaPayload(4096, (byte) 0xFF);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(imageBytes), "image/jpeg",
                imageBytes.length);

        mediaService.completeUpload(negotiation.assetId(), "abc123");
        mediaJobService.drainQueue();

        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(negotiation.assetId()).orElseThrow();
        assertEquals("ready", asset.status);
        assertEquals(2000, asset.width);
        assertEquals(1500, asset.height);

        List<MediaDerivative> derivatives = mediaDerivativeRepository.findByAsset(asset.id);
        assertEquals(4, derivatives.stream().filter(MediaDerivative::isImageDerivative).count());

        MediaQuota quota = mediaQuotaRepository.findOrCreateForCurrentTenant();
        long derivativeBytes = derivatives.stream().mapToLong(d -> d.fileSize).sum();
        assertEquals(asset.fileSize + derivativeBytes, quota.usedBytes.longValue());

        MediaService.MediaDownload download = mediaService.generateSignedUrl(asset.id, null);
        assertNotNull(download.url());
        assertEquals(4, download.remainingAttempts());

        MediaAsset refreshed = mediaAssetRepository.findByIdAndTenant(asset.id).orElseThrow();
        assertEquals(1, refreshed.downloadAttempts);
        assertEquals(1, mediaAccessLogRepository.findByAsset(asset.id).size());
    }

    @Test
    @Transactional
    void videoPipelineGeneratesHlsSegments() {
        MediaService.UploadRequest request = new MediaService.UploadRequest("demo.mp4", "video/mp4", 8192L, "video",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        byte[] videoBytes = buildFakeMediaPayload(8192, (byte) 0x00);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(videoBytes), "video/mp4",
                videoBytes.length);

        mediaService.completeUpload(negotiation.assetId(), null);
        mediaJobService.drainQueue();

        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(negotiation.assetId()).orElseThrow();
        assertEquals("ready", asset.status);
        assertEquals(120, asset.durationSeconds);

        List<MediaDerivative> derivatives = mediaDerivativeRepository.findByAsset(asset.id);
        long playlistCount = derivatives.stream().filter(d -> d.derivativeType.startsWith("hls_")).count();
        long segmentCount = derivatives.stream().filter(d -> d.derivativeType.endsWith("_segment")).count();
        assertTrue(segmentCount > 0);
        assertTrue(playlistCount >= 4); // master + 3 variants

        mediaService.deleteAsset(asset.id);
        assertTrue(mediaAssetRepository.findByIdAndTenant(asset.id).isEmpty());
        MediaQuota quota = mediaQuotaRepository.findOrCreateForCurrentTenant();
        assertEquals(0L, quota.usedBytes.longValue());
    }

    @Test
    @Transactional
    void quotaEnforcementRejectsOversizedUploads() {
        MediaQuota quota = mediaQuotaRepository.findOrCreateForCurrentTenant();
        quota.quotaBytes = 1024L;
        quota.usedBytes = 0L;
        quota.persist();

        assertThrows(MediaQuotaExceededException.class, () -> mediaService
                .negotiateUpload(new MediaService.UploadRequest("huge.mp4", "video/mp4", 2048L, "video", null)));
    }

    private byte[] buildFakeMediaPayload(int size, byte fillByte) {
        byte[] payload = new byte[size];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = fillByte;
        }
        if (payload.length > 2) {
            payload[0] = (byte) 0xFF;
            payload[1] = (byte) 0xD8;
            payload[payload.length - 2] = (byte) 0xFF;
            payload[payload.length - 1] = (byte) 0xD9;
        }
        return payload;
    }
}
