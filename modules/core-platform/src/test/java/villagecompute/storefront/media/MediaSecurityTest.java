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
import villagecompute.storefront.data.models.MediaQuota;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.MediaAssetRepository;
import villagecompute.storefront.data.repositories.MediaQuotaRepository;
import villagecompute.storefront.media.exceptions.MediaAssetNotFoundException;
import villagecompute.storefront.media.exceptions.MediaQuotaExceededException;
import villagecompute.storefront.services.MediaService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Security-focused tests for media pipeline validating: - Tenant-specific path enforcement - Size and MIME validation -
 * Quota enforcement - Cross-tenant isolation - Path traversal prevention - Hashed filename requirements
 *
 * References: - Task I2.T7 acceptance criteria - Architecture §3.6 tenant isolation
 */
@QuarkusTest
public class MediaSecurityTest {

    @Inject
    MediaService mediaService;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MediaQuotaRepository mediaQuotaRepository;

    @Inject
    StubMediaStorageClient storageClient;

    private Tenant tenant1;
    private Tenant tenant2;

    @BeforeEach
    @Transactional
    void setUpTenants() {
        // Create tenant 1
        tenant1 = new Tenant();
        tenant1.subdomain = "security-test-1-" + UUID.randomUUID().toString().substring(0, 8);
        tenant1.name = "Security Test Tenant 1";
        tenant1.status = "active";
        tenant1.createdAt = OffsetDateTime.now();
        tenant1.updatedAt = tenant1.createdAt;
        tenant1.persist();

        // Create tenant 2
        tenant2 = new Tenant();
        tenant2.subdomain = "security-test-2-" + UUID.randomUUID().toString().substring(0, 8);
        tenant2.name = "Security Test Tenant 2";
        tenant2.status = "active";
        tenant2.createdAt = OffsetDateTime.now();
        tenant2.updatedAt = tenant2.createdAt;
        tenant2.persist();
    }

    @AfterEach
    void cleanupContext() {
        TenantContext.clear();
        storageClient.clear();
    }

    // ========================================
    // Tenant Path Isolation Tests
    // ========================================

    @Test
    @Transactional
    void storageKeysMustIncludeTenantPrefix() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("test.jpg", "image/jpeg", 1024L, "image",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);

        // Assert: Storage key starts with tenant ID
        assertTrue(negotiation.storageKey().startsWith(tenant1.id.toString()),
                "Storage key must start with tenant ID: " + negotiation.storageKey());

        // Assert: Key includes media type and asset ID
        assertTrue(negotiation.storageKey().contains("/media/image/"), "Storage key must include /media/image/ path");
        assertTrue(negotiation.storageKey().contains(negotiation.assetId().toString()),
                "Storage key must include asset ID");
    }

    @Test
    @Transactional
    void storageKeysIncludeHashedFilenames() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("product-photo.jpg", "image/jpeg", 2048L,
                "image", null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);

        // Assert: Storage key contains both hash prefix and sanitized filename
        String storageKey = negotiation.storageKey();
        assertTrue(storageKey.matches(".*[0-9a-f]{12}_.*\\.jpg$"),
                "Storage key must include 12-char hash prefix before filename: " + storageKey);
    }

    @Test
    @Transactional
    void tenantCannotAccessOtherTenantAssets() {
        // Create asset as tenant1
        TenantContext.setCurrentTenantId(tenant1.id);
        MediaService.UploadRequest request = new MediaService.UploadRequest("secure.jpg", "image/jpeg", 1024L, "image",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        UUID assetId = negotiation.assetId();

        // Simulate upload completion
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(new byte[1024]), "image/jpeg",
                1024L);
        mediaService.completeUpload(assetId, null);

        // Switch to tenant2 context
        TenantContext.setCurrentTenantId(tenant2.id);

        // Assert: Cannot access tenant1's asset
        assertThrows(MediaAssetNotFoundException.class, () -> mediaService.getAsset(assetId),
                "Tenant should not be able to access another tenant's assets");

        assertThrows(MediaAssetNotFoundException.class, () -> mediaService.generateSignedUrl(assetId, null),
                "Tenant should not be able to generate signed URLs for another tenant's assets");

        assertThrows(MediaAssetNotFoundException.class, () -> mediaService.deleteAsset(assetId),
                "Tenant should not be able to delete another tenant's assets");
    }

    // ========================================
    // Size Validation Tests
    // ========================================

    @Test
    @Transactional
    void uploadRequestRejectsZeroSizeFiles() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("empty.jpg", "image/jpeg", 0L, "image",
                null);

        // Assert: Zero size is rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("fileSize"), "Error should mention fileSize validation");
    }

    @Test
    @Transactional
    void uploadRequestRejectsNegativeSizeFiles() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("invalid.jpg", "image/jpeg", -100L, "image",
                null);

        // Assert: Negative size is rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("fileSize"), "Error should mention fileSize validation");
    }

    @Test
    @Transactional
    void quotaEnforcementRejectsOversizedUploads() {
        TenantContext.setCurrentTenantId(tenant1.id);

        // Set strict quota
        MediaQuota quota = mediaQuotaRepository.findOrCreateForCurrentTenant();
        quota.quotaBytes = 1024L; // 1 KB limit
        quota.usedBytes = 0L;
        quota.persist();

        // Attempt to upload 2 KB file
        MediaService.UploadRequest request = new MediaService.UploadRequest("large.jpg", "image/jpeg", 2048L, "image",
                null);

        // Assert: Upload exceeding quota is rejected
        MediaQuotaExceededException exception = assertThrows(MediaQuotaExceededException.class,
                () -> mediaService.negotiateUpload(request));
        assertEquals(1024L, exception.getRemainingBytes(), "Exception should report correct remaining bytes");
    }

    @Test
    @Transactional
    void quotaEnforcementConsidersExistingUsage() {
        TenantContext.setCurrentTenantId(tenant1.id);

        // Set quota with existing usage
        MediaQuota quota = mediaQuotaRepository.findOrCreateForCurrentTenant();
        quota.quotaBytes = 10240L; // 10 KB total
        quota.usedBytes = 9216L; // 9 KB already used
        quota.persist();

        // Attempt to upload 2 KB file (would exceed 10 KB total)
        MediaService.UploadRequest request = new MediaService.UploadRequest("file.jpg", "image/jpeg", 2048L, "image",
                null);

        // Assert: Upload rejected due to cumulative usage
        assertThrows(MediaQuotaExceededException.class, () -> mediaService.negotiateUpload(request));
    }

    // ========================================
    // MIME Type Validation Tests
    // ========================================

    @Test
    @Transactional
    void uploadRequestRejectsEmptyMimeType() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("file.jpg", "", 1024L, "image", null);

        // Assert: Empty MIME type is rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("contentType"), "Error should mention contentType validation");
    }

    @Test
    @Transactional
    void uploadRequestRejectsNullMimeType() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("file.jpg", null, 1024L, "image", null);

        // Assert: Null MIME type is rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("contentType"), "Error should mention contentType validation");
    }

    @Test
    @Transactional
    void uploadRequestRejectsNonImageMimeForImageAssets() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("file.jpg", "video/mp4", 1024L, "image",
                null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("image/*"), "Mismatch should mention image MIME requirement");
    }

    @Test
    @Transactional
    void uploadRequestRejectsNonVideoMimeForVideoAssets() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("clip.mp4", "image/png", 2048L, "video",
                null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("video/*"), "Mismatch should mention video MIME requirement");
    }

    // ========================================
    // Asset Type Validation Tests
    // ========================================

    @Test
    @Transactional
    void uploadRequestRejectsUnsupportedAssetTypes() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("file.pdf", "application/pdf", 1024L,
                "document", null);

        // Assert: Unsupported asset type is rejected
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> mediaService.negotiateUpload(request));
        assertTrue(exception.getMessage().contains("Unsupported asset type"),
                "Error should mention unsupported asset type");
    }

    @Test
    @Transactional
    void uploadRequestNormalizesAssetTypeCase() {
        TenantContext.setCurrentTenantId(tenant1.id);

        // Test uppercase asset type
        MediaService.UploadRequest request = new MediaService.UploadRequest("file.jpg", "image/jpeg", 1024L, "IMAGE",
                null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);

        // Assert: Asset type is normalized to lowercase
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(negotiation.assetId()).orElseThrow();
        assertEquals("image", asset.assetType, "Asset type should be normalized to lowercase");
    }

    // ========================================
    // Filename Sanitization Tests
    // ========================================

    @Test
    @Transactional
    void uploadRequestSanitizesFilenamesWithSpecialChars() {
        TenantContext.setCurrentTenantId(tenant1.id);

        // Test filename with path traversal attempt
        MediaService.UploadRequest request = new MediaService.UploadRequest("../../etc/passwd.jpg", "image/jpeg", 1024L,
                "image", null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);

        // Assert: Storage key is tenant-scoped and sanitized (slashes converted to dashes)
        assertTrue(negotiation.storageKey().startsWith(tenant1.id.toString()), "Storage key must start with tenant ID");
        assertTrue(negotiation.storageKey().matches(".*[0-9a-f]{12}_[a-z0-9._-]+\\.jpg$"),
                "Sanitized filename should only contain safe characters (slashes converted to dashes)");
    }

    @Test
    @Transactional
    void uploadRequestSanitizesFilenamesWithUnicode() {
        TenantContext.setCurrentTenantId(tenant1.id);

        MediaService.UploadRequest request = new MediaService.UploadRequest("café-photo.jpg", "image/jpeg", 1024L,
                "image", null);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);

        // Assert: Unicode characters are normalized/sanitized
        String storageKey = negotiation.storageKey();
        assertTrue(storageKey.matches(".*[0-9a-f]{12}_[a-z0-9._-]+$"),
                "Sanitized filename should only contain safe characters: " + storageKey);
    }

    // ========================================
    // Signed URL Security Tests
    // ========================================

    @Test
    @Transactional
    void signedUrlsIncludeExpiryTimestamp() {
        TenantContext.setCurrentTenantId(tenant1.id);

        // Create and complete asset
        MediaService.UploadRequest request = new MediaService.UploadRequest("test.jpg", "image/jpeg", 1024L, "image",
                5);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(new byte[1024]), "image/jpeg",
                1024L);

        MediaAsset asset = mediaService.completeUpload(negotiation.assetId(), null);
        asset.status = "ready"; // Simulate processing complete
        asset.persist();

        // Generate signed URL
        MediaService.MediaDownload download = mediaService.generateSignedUrl(negotiation.assetId(), null);

        // Assert: Expiry is in the future
        assertTrue(download.expiresAt().isAfter(OffsetDateTime.now()), "Signed URL expiry must be in the future");

        // Assert: URL is not null/empty
        assertNotNull(download.url(), "Signed URL must not be null");
        assertFalse(download.url().isBlank(), "Signed URL must not be blank");
    }

    @Test
    @Transactional
    void signedUrlsEnforceDownloadLimits() {
        TenantContext.setCurrentTenantId(tenant1.id);

        // Create asset with max 2 download attempts
        MediaService.UploadRequest request = new MediaService.UploadRequest("limited.jpg", "image/jpeg", 1024L, "image",
                2);
        MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(request);
        storageClient.uploadMedia(negotiation.storageKey(), new ByteArrayInputStream(new byte[1024]), "image/jpeg",
                1024L);

        MediaAsset asset = mediaService.completeUpload(negotiation.assetId(), null);
        asset.status = "ready";
        asset.persist();

        // First download
        MediaService.MediaDownload download1 = mediaService.generateSignedUrl(negotiation.assetId(), null);
        assertEquals(1, download1.remainingAttempts(), "First download should have 1 remaining");

        // Second download
        MediaService.MediaDownload download2 = mediaService.generateSignedUrl(negotiation.assetId(), null);
        assertEquals(0, download2.remainingAttempts(), "Second download should have 0 remaining");

        // Third download should fail
        assertThrows(Exception.class, () -> mediaService.generateSignedUrl(negotiation.assetId(), null),
                "Download limit exceeded should throw exception");
    }
}
