package villagecompute.storefront.services;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.MediaAccessLog;
import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.data.models.MediaDerivative;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.MediaAccessLogRepository;
import villagecompute.storefront.data.repositories.MediaAssetRepository;
import villagecompute.storefront.data.repositories.MediaDerivativeRepository;
import villagecompute.storefront.data.repositories.MediaQuotaRepository;
import villagecompute.storefront.media.MediaStorageClient;
import villagecompute.storefront.media.MediaStoragePathBuilder;
import villagecompute.storefront.media.exceptions.MediaAssetNotFoundException;
import villagecompute.storefront.media.exceptions.MediaDownloadLimitExceededException;
import villagecompute.storefront.media.exceptions.MediaQuotaExceededException;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;

/**
 * Tenant-scoped media orchestration service for upload negotiation, quota enforcement, signed URLs, and lifecycle
 * management.
 */
@ApplicationScoped
public class MediaService {

    private static final Logger LOG = Logger.getLogger(MediaService.class);
    private static final List<String> SUPPORTED_ASSET_TYPES = List.of("image", "video");

    @ConfigProperty(
            name = "media.upload-url.expiry-minutes",
            defaultValue = "15")
    int uploadUrlExpiryMinutes;

    @ConfigProperty(
            name = "media.signed-url.expiry-hours",
            defaultValue = "24")
    int signedUrlExpiryHours;

    @ConfigProperty(
            name = "media.signed-url.max-download-attempts",
            defaultValue = "5")
    int defaultMaxDownloadAttempts;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MediaDerivativeRepository mediaDerivativeRepository;

    @Inject
    MediaAccessLogRepository mediaAccessLogRepository;

    @Inject
    MediaQuotaRepository mediaQuotaRepository;

    @Inject
    MediaJobService mediaJobService;

    @Inject
    MediaStorageClient mediaStorageClient;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Request payload for upload negotiation.
     *
     * @param filename
     *            original filename
     * @param contentType
     *            MIME type
     * @param fileSize
     *            size in bytes
     * @param assetType
     *            asset type (image|video)
     * @param maxDownloadAttempts
     *            optional override for download attempts
     */
    public record UploadRequest(String filename, String contentType, long fileSize, String assetType,
            Integer maxDownloadAttempts) {
    }

    /**
     * Negotiation response containing presigned upload URL.
     *
     * @param assetId
     *            asset identifier
     * @param storageKey
     *            storage key for uploaded object
     * @param presignedUrl
     *            presigned upload metadata
     * @param remainingQuotaBytes
     *            remaining bytes for tenant quota
     */
    public record MediaUploadNegotiation(UUID assetId, String storageKey,
            MediaStorageClient.PresignedUploadUrl presignedUrl, long remainingQuotaBytes) {
    }

    /**
     * Paginated media assets response.
     */
    public record MediaAssetPage(List<MediaAsset> assets, long total, int page, int size) {
    }

    /**
     * Media asset with derivatives.
     */
    public record MediaAssetView(MediaAsset asset, List<MediaDerivative> derivatives) {
    }

    /**
     * Signed download details.
     */
    public record MediaDownload(String url, OffsetDateTime expiresAt, int remainingAttempts) {
    }

    /**
     * Negotiate upload by creating asset metadata, enforcing quota, and generating presigned URL.
     */
    @Transactional
    public MediaUploadNegotiation negotiateUpload(UploadRequest request) {
        validateUploadRequest(request);

        String normalizedType = request.assetType().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_ASSET_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Unsupported asset type: " + request.assetType());
        }

        if (!mediaQuotaRepository.hasAvailableQuota(request.fileSize())) {
            long remaining = mediaQuotaRepository.getRemainingQuota();
            meterRegistry.counter("media.quota.exceeded", "tenant", currentTenantId().toString()).increment();
            throw new MediaQuotaExceededException("Upload exceeds tenant quota", remaining);
        }

        Tenant tenant = Tenant.findById(currentTenantId());

        MediaAsset asset = new MediaAsset();
        asset.tenant = tenant;
        asset.assetType = normalizedType;
        asset.originalFilename = request.filename();
        asset.mimeType = request.contentType();
        asset.fileSize = request.fileSize();
        asset.status = "uploading";
        asset.maxDownloadAttempts = request.maxDownloadAttempts() != null ? request.maxDownloadAttempts()
                : defaultMaxDownloadAttempts;
        asset.storageKey = String.format("%s/media/%s/pending/%s", tenant.id, normalizedType, UUID.randomUUID());

        mediaAssetRepository.persist(asset);
        mediaAssetRepository.flush();
        asset.storageKey = MediaStoragePathBuilder.buildOriginalKey(tenant.id, normalizedType, asset.id,
                request.filename());

        Duration expiry = Duration.ofMinutes(uploadUrlExpiryMinutes);
        MediaStorageClient.PresignedUploadUrl presigned = mediaStorageClient.getPresignedUploadUrl(asset.storageKey,
                asset.mimeType, expiry);

        long remainingQuota = mediaQuotaRepository.getRemainingQuota();
        meterRegistry.counter("media.upload.negotiate", "tenant", tenant.id.toString(), "type", normalizedType)
                .increment();

        return new MediaUploadNegotiation(asset.id, asset.storageKey, presigned, remainingQuota);
    }

    /**
     * Mark upload complete, update quota usage for original bytes, and enqueue processing job.
     */
    @Transactional
    public MediaAsset completeUpload(UUID assetId, String checksumSha256) {
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (!"uploading".equals(asset.status)) {
            throw new IllegalStateException("Asset not awaiting upload completion");
        }

        if (checksumSha256 != null && !checksumSha256.isBlank()) {
            asset.checksumSha256 = checksumSha256;
        }

        if (!Boolean.TRUE.equals(asset.originalUsageTracked)) {
            mediaQuotaRepository.updateUsage(asset.fileSize != null ? asset.fileSize : 0L);
            asset.originalUsageTracked = Boolean.TRUE;
        }

        asset.status = "pending";
        asset.processingError = null;

        JobPriority priority = "image".equals(asset.assetType) ? JobPriority.DEFAULT : JobPriority.LOW;
        mediaJobService.enqueueProcessingJob(asset.id, priority);

        meterRegistry.counter("media.upload.completed", "tenant", currentTenantId().toString(), "type", asset.assetType)
                .increment();

        return asset;
    }

    /**
     * Retrieve paginated asset summaries.
     */
    @Transactional
    public MediaAssetPage listAssets(String assetType, String status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));

        String normalizedType = assetType != null && !assetType.isBlank() ? assetType.toLowerCase(Locale.ROOT) : null;
        String normalizedStatus = status != null && !status.isBlank() ? status.toLowerCase(Locale.ROOT) : null;

        PanacheQuery<MediaAsset> query = mediaAssetRepository.search(normalizedType, normalizedStatus);
        query.page(Page.of(safePage, safeSize));

        List<MediaAsset> assets = query.list();
        long total = query.count();
        return new MediaAssetPage(assets, total, safePage, safeSize);
    }

    /**
     * Load asset with derivatives.
     */
    @Transactional
    public MediaAssetView getAsset(UUID assetId) {
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));
        List<MediaDerivative> derivatives = mediaDerivativeRepository.findByAsset(assetId);
        return new MediaAssetView(asset, derivatives);
    }

    /**
     * Generate a signed download URL for an asset or derivative.
     */
    @Transactional
    public MediaDownload generateSignedUrl(UUID assetId, String derivativeType) {
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        if (!asset.isReady()) {
            throw new IllegalStateException("Asset not ready for download");
        }

        if (asset.isDownloadLimitReached()) {
            throw new MediaDownloadLimitExceededException(0);
        }

        MediaDerivative derivative = null;
        String targetKey = asset.storageKey;

        if (derivativeType != null && !derivativeType.isBlank() && !"original".equalsIgnoreCase(derivativeType)) {
            derivative = mediaDerivativeRepository.findByAssetAndType(assetId, derivativeType)
                    .orElseThrow(() -> new IllegalArgumentException("Derivative not found: " + derivativeType));
            targetKey = derivative.storageKey;
        }

        Duration expiry = Duration.ofHours(signedUrlExpiryHours);
        String signedUrl = mediaStorageClient.getSignedDownloadUrl(targetKey, expiry);

        asset.incrementDownloadAttempts();
        mediaAssetRepository.persist(asset);

        MediaAccessLog log = new MediaAccessLog();
        log.asset = asset;
        log.tenant = asset.tenant;
        log.derivative = derivative;
        log.signatureVersion = asset.signatureVersion;
        log.expiresAt = OffsetDateTime.now().plus(expiry);
        mediaAccessLogRepository.persist(log);

        int remainingAttempts = asset.maxDownloadAttempts != null ? asset.maxDownloadAttempts - asset.downloadAttempts
                : -1;

        meterRegistry.counter("media.download.issued", "tenant", currentTenantId().toString(), "type",
                derivativeType == null ? "original" : derivativeType).increment();

        return new MediaDownload(signedUrl, log.expiresAt, remainingAttempts);
    }

    /**
     * Delete an asset, its derivatives, signed URLs, and update quota usage.
     */
    @Transactional
    public void deleteAsset(UUID assetId) {
        MediaAsset asset = mediaAssetRepository.findByIdAndTenant(assetId)
                .orElseThrow(() -> new MediaAssetNotFoundException(assetId));

        List<MediaDerivative> derivatives = mediaDerivativeRepository.findByAsset(assetId);
        long reclaimedBytes = 0L;

        for (MediaDerivative derivative : derivatives) {
            try {
                mediaStorageClient.deleteMedia(derivative.storageKey);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to delete derivative from storage: %s", derivative.storageKey);
            }
            reclaimedBytes += derivative.fileSize != null ? derivative.fileSize : 0L;
            mediaDerivativeRepository.delete(derivative);
        }

        mediaAccessLogRepository.deleteByAsset(assetId);

        try {
            mediaStorageClient.deleteMedia(asset.storageKey);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete original media from storage: %s", asset.storageKey);
        }

        if (Boolean.TRUE.equals(asset.originalUsageTracked) && asset.fileSize != null) {
            reclaimedBytes += asset.fileSize;
        }

        if (reclaimedBytes != 0) {
            mediaQuotaRepository.updateUsage(-reclaimedBytes);
        }

        mediaAssetRepository.delete(asset);

        meterRegistry.counter("media.asset.deleted", "tenant", currentTenantId().toString(), "type", asset.assetType)
                .increment();
    }

    private void validateUploadRequest(UploadRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Upload request required");
        }
        if (request.filename() == null || request.filename().isBlank()) {
            throw new IllegalArgumentException("filename required");
        }
        if (request.contentType() == null || request.contentType().isBlank()) {
            throw new IllegalArgumentException("contentType required");
        }
        if (request.fileSize() <= 0) {
            throw new IllegalArgumentException("fileSize must be > 0");
        }
        if (request.assetType() == null || request.assetType().isBlank()) {
            throw new IllegalArgumentException("assetType required");
        }
    }

    private UUID currentTenantId() {
        return TenantContext.getCurrentTenantId();
    }
}
