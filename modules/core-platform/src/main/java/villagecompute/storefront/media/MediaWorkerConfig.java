package villagecompute.storefront.media;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration properties for media worker and FFmpeg video processing.
 *
 * <p>
 * Maps properties from the {@code media.processing.worker} and {@code media.ffmpeg} namespaces. All values have
 * sensible defaults and can be overridden via environment variables or application.properties.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T6: Media Pipeline Video Worker</li>
 * <li>Architecture §4.1.6: FFmpeg resource isolation and worker deployment</li>
 * <li>docs/media/pipeline.md: Video processing specifications</li>
 * </ul>
 *
 * @param worker
 *            worker configuration for batch size and timeout
 * @param ffmpeg
 *            FFmpeg resource limits and concurrency settings
 */
@ConfigMapping(
        prefix = "media.processing")
public interface MediaWorkerConfig {

    /**
     * Worker configuration for job processing.
     */
    Worker worker();

    /**
     * FFmpeg resource limits and concurrency settings.
     */
    Ffmpeg ffmpeg();

    /**
     * Video processing configuration.
     */
    Video video();

    /**
     * Job dispatch interval (default: 3s).
     */
    @WithDefault("3s")
    Duration dispatchInterval();

    /**
     * Thumbnailator JPEG quality for image derivatives (default: 0.85).
     */
    Thumbnailator thumbnailator();

    /**
     * Image derivative sizes configuration.
     */
    Image image();

    interface Worker {
        /**
         * Maximum number of jobs to process per batch (default: 10).
         */
        @WithDefault("10")
        int batchSize();

        /**
         * Maximum timeout for single job processing (default: 300s = 5 minutes).
         */
        @WithDefault("300s")
        Duration timeout();

        /**
         * Enable feature flag kill switch for emergency shutdown (default: true).
         */
        @WithDefault("true")
        boolean enabled();
    }

    interface Ffmpeg {
        /**
         * Path to FFmpeg binary (default: /usr/bin/ffmpeg).
         */
        @WithDefault("/usr/bin/ffmpeg")
        String path();

        /**
         * Maximum concurrent FFmpeg processes per worker (default: 2).
         */
        @WithDefault("2")
        int maxConcurrent();

        /**
         * CPU limit per FFmpeg process (default: 2000m = 2 cores).
         */
        @WithDefault("2000m")
        String cpuLimit();

        /**
         * Memory limit per FFmpeg process (default: 4Gi).
         */
        @WithDefault("4Gi")
        String memoryLimit();

        /**
         * Timeout for individual FFmpeg process execution (default: 300s = 5 minutes).
         */
        @WithDefault("300s")
        Duration timeout();
    }

    interface Video {
        /**
         * HLS segment duration in seconds (default: 6).
         */
        Hls hls();

        /**
         * Enable MP4 output (default: true).
         */
        @WithDefault("true")
        boolean mp4Enabled();

        /**
         * Poster frame extraction timestamp in seconds (default: 1).
         */
        @WithDefault("1")
        int posterTimestamp();

        interface Hls {
            /**
             * HLS segment duration in seconds (default: 6).
             */
            @WithDefault("6")
            int segmentDuration();

            /**
             * Enable HLS packaging (default: true).
             */
            @WithDefault("true")
            boolean enabled();
        }
    }

    interface Thumbnailator {
        /**
         * JPEG quality for Thumbnailator image processing (default: 0.85).
         */
        @WithDefault("0.85")
        float quality();
    }

    interface Image {
        /**
         * Image derivative sizes configuration (default: thumbnail:150,small:400,medium:800,large:1600).
         */
        @WithDefault("thumbnail:150,small:400,medium:800,large:1600")
        String sizes();
    }
}
