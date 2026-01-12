package villagecompute.storefront.media;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import net.coobird.thumbnailator.Thumbnails;

/**
 * Media processor implementation using Thumbnailator for images and FFmpeg for video.
 *
 * <p>
 * Image processing: Generates 4 derivative sizes (thumbnail, small, medium, large) using Thumbnailator with bicubic
 * interpolation and JPEG quality 0.85.
 *
 * <p>
 * Video processing: Generates HLS variants (720p, 480p, 360p) using FFmpeg command-line tool with 6-second segments.
 * Extracts poster frame at 1 second.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T4 - Media Pipeline</li>
 * <li>Architecture §4.1.6: Thumbnailator + FFmpeg integration</li>
 * <li>docs/media/pipeline.md: Processing specifications</li>
 * </ul>
 */
@ApplicationScoped
@Alternative
public class ThumbnailatorMediaProcessor implements MediaProcessor {

    private static final Logger LOG = Logger.getLogger(ThumbnailatorMediaProcessor.class);

    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration: (\\d{2}):(\\d{2}):(\\d{2})");
    private static final Pattern DIMENSION_PATTERN = Pattern.compile("Stream.*Video.*\\s(\\d+)x(\\d+)");
    private static final Pattern BITRATE_PATTERN = Pattern.compile("bitrate: (\\d+) kb/s");

    @Inject
    MediaWorkerConfig mediaConfig;

    private String ffmpegPath;
    private float jpegQuality;
    private int hlsSegmentDuration;
    private String imageSizesConfig;
    private Map<String, Integer> imageSizes;
    private Duration ffmpegTimeout;
    private boolean hlsEnabled;
    private boolean mp4Enabled;
    private int posterTimestampSeconds;

    @PostConstruct
    void initialize() {
        ffmpegPath = mediaConfig.ffmpeg().path();
        jpegQuality = mediaConfig.thumbnailator().quality();
        hlsSegmentDuration = mediaConfig.video().hls().segmentDuration();
        imageSizesConfig = mediaConfig.image().sizes();
        ffmpegTimeout = mediaConfig.ffmpeg().timeout();
        hlsEnabled = mediaConfig.video().hls().enabled();
        mp4Enabled = mediaConfig.video().mp4Enabled();
        posterTimestampSeconds = mediaConfig.video().posterTimestamp();

        LOG.infof("Initializing media processor: ffmpeg=%s, quality=%.2f, hlsSegment=%ds", ffmpegPath, jpegQuality,
                hlsSegmentDuration);
        LOG.infof("Video config: hlsEnabled=%s, mp4Enabled=%s, timeout=%s, posterTimestamp=%ds", hlsEnabled, mp4Enabled,
                ffmpegTimeout, posterTimestampSeconds);

        // Parse image sizes configuration
        imageSizes = new LinkedHashMap<>();
        Arrays.stream(imageSizesConfig.split(",")).map(s -> s.split(":"))
                .forEach(parts -> imageSizes.put(parts[0], Integer.parseInt(parts[1])));

        LOG.infof("Image derivative sizes configured: %s", imageSizes);

        // Verify FFmpeg is available
        if (!Files.exists(Path.of(ffmpegPath))) {
            LOG.warnf("FFmpeg not found at %s - video processing will fail until binary is installed", ffmpegPath);
        } else {
            LOG.info("FFmpeg binary verified");
        }
    }

    @Override
    public List<ImageDerivative> processImage(Path sourceFile, Path outputDir) {
        LOG.infof("Processing image: source=%s, output=%s", sourceFile, outputDir);

        List<ImageDerivative> derivatives = new ArrayList<>();

        try {
            // Load source image
            BufferedImage source = ImageIO.read(sourceFile.toFile());
            if (source == null) {
                throw new MediaProcessingException("Failed to read image file: " + sourceFile);
            }

            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            LOG.infof("Source image dimensions: %dx%d", sourceWidth, sourceHeight);

            // Generate each derivative size
            for (Map.Entry<String, Integer> sizeEntry : imageSizes.entrySet()) {
                String type = sizeEntry.getKey();
                int targetWidth = sizeEntry.getValue();

                // Skip if source is smaller than target
                if (sourceWidth <= targetWidth) {
                    LOG.infof("Skipping %s derivative (source width %d <= target %d)", type, sourceWidth, targetWidth);
                    continue;
                }

                // Calculate target height maintaining aspect ratio
                int targetHeight = (int) ((double) sourceHeight / sourceWidth * targetWidth);

                Path outputPath = outputDir.resolve(type + ".jpg");

                // Generate derivative using Thumbnailator
                Thumbnails.of(sourceFile.toFile()).size(targetWidth, targetHeight).outputFormat("jpg")
                        .outputQuality(jpegQuality).toFile(outputPath.toFile());

                long fileSize = Files.size(outputPath);

                LOG.infof("Generated %s derivative: %dx%d, %d bytes", type, targetWidth, targetHeight, fileSize);

                derivatives
                        .add(new ImageDerivative(type, outputPath, targetWidth, targetHeight, fileSize, "image/jpeg"));
            }

            LOG.infof("Image processing complete: %d derivatives generated", derivatives.size());
            return derivatives;

        } catch (IOException e) {
            LOG.errorf(e, "Image processing failed: %s", sourceFile);
            throw new MediaProcessingException("Failed to process image: " + sourceFile, e);
        }
    }

    @Override
    public VideoProcessingResult processVideo(Path sourceFile, Path outputDir) {
        LOG.infof("Processing video: source=%s, output=%s", sourceFile, outputDir);

        try {
            // Extract metadata first
            VideoMetadata metadata = extractVideoMetadata(sourceFile);
            LOG.infof("Video metadata: %dx%d, %ds, codec=%s", metadata.getWidth(), metadata.getHeight(),
                    metadata.getDurationSeconds(), metadata.getCodec());

            List<HLSVariant> variants = new ArrayList<>();
            Path masterPlaylist = null;

            if (hlsEnabled) {
                // Define variant configurations (resolution:bitrate)
                Map<String, String> variantConfigs = new LinkedHashMap<>();
                variantConfigs.put("720p", "1280:720:2000k");
                variantConfigs.put("480p", "854:480:1000k");
                variantConfigs.put("360p", "640:360:500k");

                for (Map.Entry<String, String> entry : variantConfigs.entrySet()) {
                    String variantName = entry.getKey();
                    String[] config = entry.getValue().split(":");
                    int width = Integer.parseInt(config[0]);
                    int height = Integer.parseInt(config[1]);
                    String bitrate = config[2];

                    // Skip variants larger than source
                    if (width > metadata.getWidth()) {
                        LOG.infof("Skipping %s variant (source width %d < target %d)", variantName, metadata.getWidth(),
                                width);
                        continue;
                    }

                    HLSVariant variant = generateHLSVariant(sourceFile, outputDir, variantName, width, height, bitrate);
                    variants.add(variant);
                }

                masterPlaylist = outputDir.resolve("master.m3u8");
                generateMasterPlaylist(masterPlaylist, variants);
            } else {
                LOG.info("HLS packaging disabled via configuration");
            }

            Path mp4File = null;
            if (mp4Enabled) {
                mp4File = generateMp4Derivative(sourceFile, outputDir, metadata);
            } else {
                LOG.info("MP4 output disabled via configuration");
            }

            // Extract poster frame
            Path posterFrame = outputDir.resolve("poster.jpg");
            extractPosterFrame(sourceFile, posterFrame);

            LOG.infof("Video processing complete: variants=%d, masterPlaylist=%s, mp4=%s", variants.size(),
                    masterPlaylist != null, mp4File != null);
            return new VideoProcessingResult(masterPlaylist, variants, posterFrame, mp4File);

        } catch (IOException | InterruptedException e) {
            LOG.errorf(e, "Video processing failed: %s", sourceFile);
            throw new MediaProcessingException("Failed to process video: " + sourceFile, e);
        }
    }

    private HLSVariant generateHLSVariant(Path sourceFile, Path outputDir, String variantName, int width, int height,
            String bitrate) throws IOException, InterruptedException {

        String playlistName = "hls_" + variantName + ".m3u8";
        String segmentPattern = variantName + "_segment_%03d.ts";

        Path playlistPath = outputDir.resolve(playlistName);
        Path segmentDir = outputDir;

        // Build FFmpeg command
        List<String> command = Arrays.asList(ffmpegPath, "-i", sourceFile.toString(), "-vf",
                "scale=" + width + ":" + height, "-c:v", "libx264", "-b:v", bitrate, "-c:a", "aac", "-b:a", "128k",
                "-hls_time", String.valueOf(hlsSegmentDuration), "-hls_playlist_type", "vod", "-hls_segment_filename",
                segmentDir.resolve(segmentPattern).toString(), playlistPath.toString());

        LOG.infof("Executing FFmpeg for %s variant: %s", variantName, String.join(" ", command));

        runFfmpegCommand(command, variantName + " HLS variant");

        // Collect segment files
        List<Path> segmentFiles = new ArrayList<>();
        long totalSize = 0;

        int segmentIndex = 0;
        while (true) {
            Path segmentFile = segmentDir.resolve(String.format(segmentPattern.replace("%03d", "%03d"), segmentIndex));
            if (!Files.exists(segmentFile)) {
                break;
            }
            segmentFiles.add(segmentFile);
            totalSize += Files.size(segmentFile);
            segmentIndex++;
        }

        totalSize += Files.size(playlistPath);

        LOG.infof("HLS %s variant complete: %d segments, %d bytes total", variantName, segmentFiles.size(), totalSize);

        return new HLSVariant("hls_" + variantName, playlistPath, segmentFiles, width, height, totalSize);
    }

    private Path generateMp4Derivative(Path sourceFile, Path outputDir, VideoMetadata metadata)
            throws IOException, InterruptedException {
        Path mp4Path = outputDir.resolve("transcoded.mp4");
        List<String> command = new ArrayList<>(
                List.of(ffmpegPath, "-i", sourceFile.toString(), "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                        "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", mp4Path.toString()));

        LOG.infof("Executing FFmpeg for MP4 derivative: %s", String.join(" ", command));
        runFfmpegCommand(command, "MP4 derivative");

        LOG.infof("MP4 derivative generated: %s (%d bytes)", mp4Path, Files.size(mp4Path));
        return mp4Path;
    }

    private void generateMasterPlaylist(Path masterPlaylist, List<HLSVariant> variants) throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("#EXTM3U\n");
        content.append("#EXT-X-VERSION:3\n");

        for (HLSVariant variant : variants) {
            String variantName = variant.getType();
            int bandwidth = estimateBandwidth(variant);

            content.append(String.format("#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%dx%d\n", bandwidth,
                    variant.getWidth(), variant.getHeight()));
            content.append(variantName + ".m3u8\n");
        }

        Files.writeString(masterPlaylist, content.toString());
        LOG.infof("Master playlist generated: %s (%d variants)", masterPlaylist, variants.size());
    }

    private void runFfmpegCommand(List<String> command, String description) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(ffmpegTimeout.toSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            LOG.errorf("FFmpeg %s timed out after %s:%n%s", description, ffmpegTimeout, output);
            throw new MediaProcessingException("FFmpeg " + description + " timed out after " + ffmpegTimeout);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            LOG.errorf("FFmpeg %s failed (exit code %d):%n%s", description, exitCode, output);
            throw new MediaProcessingException("FFmpeg failed for " + description + " (exit code " + exitCode + ")");
        }
    }

    private int estimateBandwidth(HLSVariant variant) {
        // Rough bandwidth estimate based on resolution
        if (variant.getWidth() >= 1280) {
            return 2000000; // 2 Mbps for 720p
        } else if (variant.getWidth() >= 854) {
            return 1000000; // 1 Mbps for 480p
        } else {
            return 500000; // 500 Kbps for 360p
        }
    }

    private void extractPosterFrame(Path sourceFile, Path posterFrame) throws IOException, InterruptedException {
        List<String> command = Arrays.asList(ffmpegPath, "-i", sourceFile.toString(), "-ss",
                String.valueOf(posterTimestampSeconds), "-vframes", "1", "-q:v", "2", posterFrame.toString());

        LOG.infof("Extracting poster frame: %s", String.join(" ", command));

        runFfmpegCommand(command, "poster frame extraction");

        LOG.infof("Poster frame extracted: %s (%d bytes)", posterFrame, Files.size(posterFrame));
    }

    @Override
    public VideoMetadata extractVideoMetadata(Path sourceFile) {
        LOG.infof("Extracting video metadata: %s", sourceFile);

        try {
            // Use ffprobe to extract metadata
            List<String> command = Arrays.asList(ffmpegPath.replace("ffmpeg", "ffprobe"), "-i", sourceFile.toString());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            process.waitFor(10, TimeUnit.SECONDS);

            String metadata = output.toString();

            // Parse duration
            int durationSeconds = 0;
            Matcher durationMatcher = DURATION_PATTERN.matcher(metadata);
            if (durationMatcher.find()) {
                int hours = Integer.parseInt(durationMatcher.group(1));
                int minutes = Integer.parseInt(durationMatcher.group(2));
                int seconds = Integer.parseInt(durationMatcher.group(3));
                durationSeconds = hours * 3600 + minutes * 60 + seconds;
            }

            // Parse dimensions
            int width = 0, height = 0;
            Matcher dimensionMatcher = DIMENSION_PATTERN.matcher(metadata);
            if (dimensionMatcher.find()) {
                width = Integer.parseInt(dimensionMatcher.group(1));
                height = Integer.parseInt(dimensionMatcher.group(2));
            }

            // Parse bitrate
            long bitrate = 0;
            Matcher bitrateMatcher = BITRATE_PATTERN.matcher(metadata);
            if (bitrateMatcher.find()) {
                bitrate = Long.parseLong(bitrateMatcher.group(1)) * 1000; // Convert kb/s to bps
            }

            String codec = metadata.contains("h264") ? "h264" : "unknown";

            LOG.infof("Video metadata extracted: %dx%d, %ds, %d bps, codec=%s", width, height, durationSeconds, bitrate,
                    codec);

            return new VideoMetadata(width, height, durationSeconds, codec, bitrate);

        } catch (IOException | InterruptedException e) {
            LOG.errorf(e, "Failed to extract video metadata: %s", sourceFile);
            throw new MediaProcessingException("Failed to extract video metadata: " + sourceFile, e);
        }
    }

    @Override
    public ImageMetadata extractImageMetadata(Path sourceFile) {
        LOG.infof("Extracting image metadata: %s", sourceFile);

        try {
            BufferedImage image = ImageIO.read(sourceFile.toFile());
            if (image == null) {
                throw new MediaProcessingException("Failed to read image file: " + sourceFile);
            }

            int width = image.getWidth();
            int height = image.getHeight();

            // Determine format from file extension
            String filename = sourceFile.getFileName().toString();
            String format = filename.substring(filename.lastIndexOf('.') + 1).toUpperCase();

            LOG.infof("Image metadata extracted: %dx%d, format=%s", width, height, format);

            return new ImageMetadata(width, height, format);

        } catch (IOException e) {
            LOG.errorf(e, "Failed to extract image metadata: %s", sourceFile);
            throw new MediaProcessingException("Failed to extract image metadata: " + sourceFile, e);
        }
    }
}
