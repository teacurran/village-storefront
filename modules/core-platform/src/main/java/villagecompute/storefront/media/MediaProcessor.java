package villagecompute.storefront.media;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for media processing operations (image resizing, video transcoding).
 *
 * <p>
 * Implementations wrap external tools like Thumbnailator (images) and FFmpeg (video) to generate derivatives with
 * specified dimensions and formats.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Architecture §4.1.6: Thumbnailator for images, FFmpeg for video with HLS</li>
 * <li>Foundation: Deterministic outputs for variant generation</li>
 * </ul>
 */
public interface MediaProcessor {

    /**
     * Process an image to generate derivatives at specified sizes.
     *
     * @param sourceFile
     *            path to source image file
     * @param outputDir
     *            directory to write derivatives
     * @return list of generated derivative files with metadata
     */
    List<ImageDerivative> processImage(Path sourceFile, Path outputDir);

    /**
     * Process a video to generate HLS segments and poster frame.
     *
     * @param sourceFile
     *            path to source video file
     * @param outputDir
     *            directory to write derivatives
     * @return video processing result with HLS master playlist and poster
     */
    VideoProcessingResult processVideo(Path sourceFile, Path outputDir);

    /**
     * Extract video metadata (dimensions, duration, codec).
     *
     * @param sourceFile
     *            path to video file
     * @return video metadata
     */
    VideoMetadata extractVideoMetadata(Path sourceFile);

    /**
     * Extract image metadata (dimensions, format).
     *
     * @param sourceFile
     *            path to image file
     * @return image metadata
     */
    ImageMetadata extractImageMetadata(Path sourceFile);

    /**
     * Image derivative metadata.
     */
    class ImageDerivative {
        private final String type; // thumbnail, small, medium, large
        private final Path filePath;
        private final int width;
        private final int height;
        private final long fileSize;
        private final String mimeType;

        public ImageDerivative(String type, Path filePath, int width, int height, long fileSize, String mimeType) {
            this.type = type;
            this.filePath = filePath;
            this.width = width;
            this.height = height;
            this.fileSize = fileSize;
            this.mimeType = mimeType;
        }

        public String getType() {
            return type;
        }

        public Path getFilePath() {
            return filePath;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public long getFileSize() {
            return fileSize;
        }

        public String getMimeType() {
            return mimeType;
        }
    }

    /**
     * Video processing result.
     */
    class VideoProcessingResult {
        private final Path masterPlaylist; // HLS master .m3u8
        private final List<HLSVariant> variants;
        private final Path posterFrame;

        public VideoProcessingResult(Path masterPlaylist, List<HLSVariant> variants, Path posterFrame) {
            this.masterPlaylist = masterPlaylist;
            this.variants = variants;
            this.posterFrame = posterFrame;
        }

        public Path getMasterPlaylist() {
            return masterPlaylist;
        }

        public List<HLSVariant> getVariants() {
            return variants;
        }

        public Path getPosterFrame() {
            return posterFrame;
        }
    }

    /**
     * HLS variant stream metadata.
     */
    class HLSVariant {
        private final String type; // hls_720p, hls_480p, hls_360p
        private final Path playlistPath;
        private final List<Path> segmentFiles;
        private final int width;
        private final int height;
        private final long totalSize;

        public HLSVariant(String type, Path playlistPath, List<Path> segmentFiles, int width, int height,
                long totalSize) {
            this.type = type;
            this.playlistPath = playlistPath;
            this.segmentFiles = segmentFiles;
            this.width = width;
            this.height = height;
            this.totalSize = totalSize;
        }

        public String getType() {
            return type;
        }

        public Path getPlaylistPath() {
            return playlistPath;
        }

        public List<Path> getSegmentFiles() {
            return segmentFiles;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public long getTotalSize() {
            return totalSize;
        }
    }

    /**
     * Video metadata.
     */
    class VideoMetadata {
        private final int width;
        private final int height;
        private final int durationSeconds;
        private final String codec;
        private final long bitrate;

        public VideoMetadata(int width, int height, int durationSeconds, String codec, long bitrate) {
            this.width = width;
            this.height = height;
            this.durationSeconds = durationSeconds;
            this.codec = codec;
            this.bitrate = bitrate;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getDurationSeconds() {
            return durationSeconds;
        }

        public String getCodec() {
            return codec;
        }

        public long getBitrate() {
            return bitrate;
        }
    }

    /**
     * Image metadata.
     */
    class ImageMetadata {
        private final int width;
        private final int height;
        private final String format;

        public ImageMetadata(int width, int height, String format) {
            this.width = width;
            this.height = height;
            this.format = format;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public String getFormat() {
            return format;
        }
    }
}
