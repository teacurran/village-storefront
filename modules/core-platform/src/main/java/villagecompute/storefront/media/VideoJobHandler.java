package villagecompute.storefront.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.data.models.MediaDerivative;
import villagecompute.storefront.data.repositories.MediaAssetRepository;
import villagecompute.storefront.data.repositories.MediaDerivativeRepository;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Video job handler for FFmpeg-based video transcoding and HLS packaging.
 *
 * <p>
 * Orchestrates video processing with strict resource controls:
 * <ul>
 * <li>Semaphore-based concurrency limiting (max 2 concurrent FFmpeg processes)</li>
 * <li>Timeout enforcement via ProcessBuilder forced termination</li>
 * <li>Metadata extraction, HLS variant generation, and poster frame extraction</li>
 * <li>Comprehensive metrics instrumentation for observability</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T6: Media Pipeline Video Worker</li>
 * <li>Architecture §4.1.6: FFmpeg resource isolation and worker deployment</li>
 * <li>docs/architecture/background_jobs.md: FFmpeg worker isolation pattern</li>
 * </ul>
 */
@ApplicationScoped
public class VideoJobHandler {

    private static final Logger LOG = Logger.getLogger(VideoJobHandler.class);

    @Inject
    MediaWorkerConfig config;

    @Inject
    MediaProcessor mediaProcessor;

    @Inject
    MediaStorageClient storageClient;

    @Inject
    MediaAssetRepository mediaAssetRepository;

    @Inject
    MediaDerivativeRepository mediaDerivativeRepository;

    @Inject
    MeterRegistry meterRegistry;

    private Semaphore ffmpegSemaphore;
    private Counter transcodeSuccessCounter;
    private Counter transcodeFailureCounter;
    private Counter transcodeTimeoutCounter;
    private Counter semaphoreBlockedCounter;
    private Timer transcodeTimer;

    @PostConstruct
    void initialize() {
        int maxConcurrent = config.ffmpeg().maxConcurrent();
        ffmpegSemaphore = new Semaphore(maxConcurrent, true);

        LOG.infof("VideoJobHandler initialized: maxConcurrent=%d, timeout=%s", maxConcurrent,
                config.ffmpeg().timeout());

        // Initialize metrics
        transcodeSuccessCounter = meterRegistry.counter("media.video.transcode.success");
        transcodeFailureCounter = meterRegistry.counter("media.video.transcode.failed");
        transcodeTimeoutCounter = meterRegistry.counter("media.video.transcode.timeout");
        semaphoreBlockedCounter = meterRegistry.counter("media.video.semaphore.blocked");
        transcodeTimer = meterRegistry.timer("media.video.transcode.duration");
    }

    /**
     * Process video asset: download, transcode, upload derivatives, persist metadata.
     *
     * @param asset
     *            media asset to process
     * @param sourceFile
     *            path to downloaded source video file
     * @param tempDir
     *            temporary directory for processing
     * @param tenantId
     *            tenant context for metrics
     * @return total size of uploaded derivatives
     * @throws IOException
     *             if file operations fail
     */
    @ActivateRequestContext
    public long processVideo(MediaAsset asset, Path sourceFile, Path tempDir, UUID tenantId) throws IOException {
        // Check feature flag kill switch
        if (!config.worker().enabled()) {
            LOG.warnf("Video processing disabled by feature flag, skipping asset %s", asset.id);
            throw new MediaProcessingException("Video processing disabled by feature flag");
        }

        // Acquire semaphore permit
        boolean acquired = false;
        try {
            LOG.infof("Acquiring FFmpeg semaphore for asset %s (available permits: %d)", asset.id,
                    ffmpegSemaphore.availablePermits());

            long semaphoreWaitStart = System.currentTimeMillis();
            acquired = ffmpegSemaphore.tryAcquire(config.worker().timeout().toMillis(), TimeUnit.MILLISECONDS);

            if (!acquired) {
                long waitDuration = System.currentTimeMillis() - semaphoreWaitStart;
                LOG.errorf("Failed to acquire FFmpeg semaphore within timeout %s (waited %dms) for asset %s",
                        config.worker().timeout(), waitDuration, asset.id);
                semaphoreBlockedCounter.increment();
                throw new MediaProcessingException("FFmpeg semaphore acquisition timeout after " + waitDuration + "ms");
            }

            long waitDuration = System.currentTimeMillis() - semaphoreWaitStart;
            LOG.infof("FFmpeg semaphore acquired in %dms for asset %s", waitDuration, asset.id);

            // Process video with timeout enforcement
            return transcodeTimer.record(() -> {
                try {
                    return processVideoInternal(asset, sourceFile, tempDir, tenantId);
                } catch (IOException e) {
                    throw new RuntimeException("Video processing failed", e);
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.errorf(e, "Interrupted while waiting for FFmpeg semaphore for asset %s", asset.id);
            throw new MediaProcessingException("Semaphore acquisition interrupted", e);
        } finally {
            if (acquired) {
                ffmpegSemaphore.release();
                LOG.infof("FFmpeg semaphore released for asset %s (available permits: %d)", asset.id,
                        ffmpegSemaphore.availablePermits());
            }
        }
    }

    private long processVideoInternal(MediaAsset asset, Path sourceFile, Path tempDir, UUID tenantId)
            throws IOException {
        LOG.infof("Starting video transcoding for asset %s", asset.id);

        try {
            // Extract metadata
            MediaProcessor.VideoMetadata metadata = mediaProcessor.extractVideoMetadata(sourceFile);
            if (metadata != null) {
                asset.width = metadata.getWidth();
                asset.height = metadata.getHeight();
                asset.durationSeconds = metadata.getDurationSeconds();
                LOG.infof("Video metadata extracted: %dx%d, %ds", metadata.getWidth(), metadata.getHeight(),
                        metadata.getDurationSeconds());
            }

            // Process video (HLS variants + poster)
            MediaProcessor.VideoProcessingResult result = mediaProcessor.processVideo(sourceFile, tempDir);

            // Upload all derivatives
            long totalSize = uploadVideoDerivatives(asset, result, tenantId);

            LOG.infof("Video transcoding complete for asset %s: %d variants, %d bytes", asset.id,
                    result.getVariants().size(), totalSize);

            transcodeSuccessCounter.increment();
            return totalSize;

        } catch (MediaProcessingException e) {
            String message = e.getMessage();
            boolean timedOut = message != null && message.toLowerCase(Locale.ROOT).contains("timeout");
            LOG.errorf(e, "Video processing failed for asset %s (timeout=%s)", asset.id, timedOut);
            if (timedOut) {
                transcodeTimeoutCounter.increment();
            } else {
                transcodeFailureCounter.increment();
            }
            throw e;
        } catch (Exception e) {
            transcodeFailureCounter.increment();
            LOG.errorf(e, "Video processing failed for asset %s", asset.id);
            throw new MediaProcessingException("Video processing failed: " + e.getMessage(), e);
        }
    }

    private long uploadVideoDerivatives(MediaAsset asset, MediaProcessor.VideoProcessingResult result, UUID tenantId)
            throws IOException {
        long totalSize = 0;

        // Upload master playlist when HLS is enabled
        if (result.getMasterPlaylist().isPresent()) {
            Path masterPlaylist = result.getMasterPlaylist().get();
            String masterKey = buildDerivativeKey(asset, "hls_master", "master.m3u8");
            try (InputStream data = Files.newInputStream(masterPlaylist)) {
                long masterSize = Files.size(masterPlaylist);
                storageClient.uploadMedia(masterKey, data, "application/vnd.apple.mpegurl", masterSize);

                MediaDerivative masterEntity = new MediaDerivative();
                masterEntity.asset = asset;
                masterEntity.tenant = asset.tenant;
                masterEntity.derivativeType = "hls_master";
                masterEntity.storageKey = masterKey;
                masterEntity.mimeType = "application/vnd.apple.mpegurl";
                masterEntity.fileSize = masterSize;
                mediaDerivativeRepository.persist(masterEntity);
                totalSize += masterSize;

                LOG.infof("Uploaded HLS master playlist: %s (%d bytes)", masterKey, masterSize);
            }
        } else {
            LOG.info("Skipping HLS master upload (HLS disabled)");
        }

        // Upload HLS variants (playlists + segments)
        for (MediaProcessor.HLSVariant variant : result.getVariants()) {
            // Upload variant playlist
            String variantKey = buildDerivativeKey(asset, variant.getType(), variant.getType() + ".m3u8");
            long playlistSize = Files.size(variant.getPlaylistPath());
            try (InputStream data = Files.newInputStream(variant.getPlaylistPath())) {
                storageClient.uploadMedia(variantKey, data, "application/vnd.apple.mpegurl", playlistSize);
            }
            totalSize += playlistSize;

            MediaDerivative variantEntity = new MediaDerivative();
            variantEntity.asset = asset;
            variantEntity.tenant = asset.tenant;
            variantEntity.derivativeType = variant.getType();
            variantEntity.storageKey = variantKey;
            variantEntity.mimeType = "application/vnd.apple.mpegurl";
            variantEntity.fileSize = playlistSize;
            variantEntity.width = variant.getWidth();
            variantEntity.height = variant.getHeight();
            mediaDerivativeRepository.persist(variantEntity);

            LOG.infof("Uploaded HLS variant playlist: %s (%d bytes)", variantKey, playlistSize);

            // Upload segments
            if (variant.getSegmentFiles() != null) {
                for (Path segment : variant.getSegmentFiles()) {
                    String segmentKey = buildDerivativeKey(asset, variant.getType() + "_segment",
                            segment.getFileName().toString());
                    long segmentSize = Files.size(segment);
                    try (InputStream data = Files.newInputStream(segment)) {
                        storageClient.uploadMedia(segmentKey, data, "video/mp2t", segmentSize);
                    }

                    MediaDerivative segmentEntity = new MediaDerivative();
                    segmentEntity.asset = asset;
                    segmentEntity.tenant = asset.tenant;
                    segmentEntity.derivativeType = variant.getType() + "_segment";
                    segmentEntity.storageKey = segmentKey;
                    segmentEntity.mimeType = "video/mp2t";
                    segmentEntity.fileSize = segmentSize;
                    segmentEntity.width = variant.getWidth();
                    segmentEntity.height = variant.getHeight();
                    mediaDerivativeRepository.persist(segmentEntity);
                    totalSize += segmentSize;
                }
                LOG.infof("Uploaded %d HLS segments for variant %s", variant.getSegmentFiles().size(),
                        variant.getType());
            }
        }

        // Upload poster frame
        String posterKey = buildDerivativeKey(asset, "poster", "poster.jpg");
        try (InputStream data = Files.newInputStream(result.getPosterFrame())) {
            long posterSize = Files.size(result.getPosterFrame());
            storageClient.uploadMedia(posterKey, data, "image/jpeg", posterSize);

            MediaDerivative posterEntity = new MediaDerivative();
            posterEntity.asset = asset;
            posterEntity.tenant = asset.tenant;
            posterEntity.derivativeType = "poster";
            posterEntity.storageKey = posterKey;
            posterEntity.mimeType = "image/jpeg";
            posterEntity.fileSize = posterSize;
            mediaDerivativeRepository.persist(posterEntity);
            totalSize += posterSize;

            LOG.infof("Uploaded poster frame: %s (%d bytes)", posterKey, posterSize);
        }

        // Upload MP4 derivative if generated
        if (result.getMp4File().isPresent()) {
            Path mp4File = result.getMp4File().get();
            String mp4Key = buildDerivativeKey(asset, "mp4",
                    asset.originalFilename != null ? asset.originalFilename : "transcoded.mp4");
            try (InputStream data = Files.newInputStream(mp4File)) {
                long mp4Size = Files.size(mp4File);
                storageClient.uploadMedia(mp4Key, data, "video/mp4", mp4Size);

                MediaDerivative mp4Entity = new MediaDerivative();
                mp4Entity.asset = asset;
                mp4Entity.tenant = asset.tenant;
                mp4Entity.derivativeType = "mp4";
                mp4Entity.storageKey = mp4Key;
                mp4Entity.mimeType = "video/mp4";
                mp4Entity.fileSize = mp4Size;
                mediaDerivativeRepository.persist(mp4Entity);
                totalSize += mp4Size;

                LOG.infof("Uploaded MP4 derivative: %s (%d bytes)", mp4Key, mp4Size);
            }
        } else {
            LOG.info("No MP4 derivative generated (mp4 output disabled)");
        }

        return totalSize;
    }

    private String buildDerivativeKey(MediaAsset asset, String derivativeType, String filename) {
        return MediaStoragePathBuilder.buildDerivativeKey(asset.tenant.id, asset.assetType, asset.id, derivativeType,
                filename);
    }
}
