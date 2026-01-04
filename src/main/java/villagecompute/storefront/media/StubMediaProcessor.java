package villagecompute.storefront.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

/**
 * Stub implementation of MediaProcessor for testing without FFmpeg/Thumbnailator dependencies.
 *
 * <p>
 * Generates fake derivative files with predictable sizes and metadata. Used in unit tests to verify job processing
 * logic without invoking native binaries.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Acceptance Criteria: "tests simulate FFmpeg via stub"</li>
 * </ul>
 */
@ApplicationScoped
@DefaultBean
public class StubMediaProcessor implements MediaProcessor {

    private static final Logger LOG = Logger.getLogger(StubMediaProcessor.class);

    private static final int[] IMAGE_SIZES = {150, 400, 800, 1600}; // thumbnail, small, medium, large
    private static final String[] IMAGE_SIZE_NAMES = {"thumbnail", "small", "medium", "large"};

    @Override
    public List<ImageDerivative> processImage(Path sourceFile, Path outputDir) {
        LOG.infof("Stub image processing: source=%s, output=%s", sourceFile, outputDir);

        List<ImageDerivative> derivatives = new ArrayList<>();

        try {
            // Create output directory
            Files.createDirectories(outputDir);

            // Generate fake derivatives at each size
            for (int i = 0; i < IMAGE_SIZES.length; i++) {
                String sizeName = IMAGE_SIZE_NAMES[i];
                int size = IMAGE_SIZES[i];

                String filename = String.format("%s_%s.jpg",
                        sourceFile.getFileName().toString().replaceFirst("[.][^.]+$", ""), sizeName);
                Path derivativePath = outputDir.resolve(filename);

                // Write fake image data
                byte[] fakeData = generateFakeImageData(size);
                Files.write(derivativePath, fakeData);

                derivatives
                        .add(new ImageDerivative(sizeName, derivativePath, size, size, fakeData.length, "image/jpeg"));
                LOG.infof("Generated stub image derivative: %s (%dx%d)", filename, size, size);
            }

            return derivatives;
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate stub image derivatives", e);
        }
    }

    @Override
    public VideoProcessingResult processVideo(Path sourceFile, Path outputDir) {
        LOG.infof("Stub video processing: source=%s, output=%s", sourceFile, outputDir);

        try {
            // Create output directory
            Files.createDirectories(outputDir);

            // Generate fake HLS master playlist
            String masterFilename = "master.m3u8";
            Path masterPlaylist = outputDir.resolve(masterFilename);
            String masterContent = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\nhls_720p.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480\nhls_480p.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\nhls_360p.m3u8\n";
            Files.writeString(masterPlaylist, masterContent);

            // Generate fake HLS variants
            List<HLSVariant> variants = new ArrayList<>();
            int[][] resolutions = {{1280, 720}, {854, 480}, {640, 360}};
            String[] variantNames = {"hls_720p", "hls_480p", "hls_360p"};

            for (int i = 0; i < variantNames.length; i++) {
                String variantName = variantNames[i];
                int width = resolutions[i][0];
                int height = resolutions[i][1];

                Path variantPlaylist = outputDir.resolve(variantName + ".m3u8");
                Path segmentDir = outputDir.resolve(variantName + "_segments");
                Files.createDirectories(segmentDir);

                // Write fake variant playlist
                String playlistContent = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nsegment_00.ts\n#EXT-X-ENDLIST\n";
                Files.writeString(variantPlaylist, playlistContent);

                // Write fake segment file
                byte[] fakeSegment = generateFakeVideoSegment(width, height);
                Path segmentFile = segmentDir.resolve("segment_00.ts");
                Files.write(segmentFile, fakeSegment);

                variants.add(new HLSVariant(variantName, variantPlaylist, List.of(segmentFile), width, height,
                        fakeSegment.length));
                LOG.infof("Generated stub HLS variant: %s (%dx%d)", variantName, width, height);
            }

            // Generate fake poster frame
            Path posterFrame = outputDir.resolve("poster.jpg");
            byte[] fakePoster = generateFakeImageData(1280);
            Files.write(posterFrame, fakePoster);

            return new VideoProcessingResult(masterPlaylist, variants, posterFrame);
        } catch (IOException e) {
            throw new RuntimeException("Failed to generate stub video derivatives", e);
        }
    }

    @Override
    public VideoMetadata extractVideoMetadata(Path sourceFile) {
        LOG.infof("Stub video metadata extraction: source=%s", sourceFile);
        // Return fake metadata
        return new VideoMetadata(1920, 1080, 120, "h264", 5000000);
    }

    @Override
    public ImageMetadata extractImageMetadata(Path sourceFile) {
        LOG.infof("Stub image metadata extraction: source=%s", sourceFile);
        // Return fake metadata
        return new ImageMetadata(2000, 1500, "JPEG");
    }

    private byte[] generateFakeImageData(int size) {
        // Generate minimal valid JPEG header + fake data
        int fakeSize = size * 100; // Approximate file size
        byte[] data = new byte[fakeSize];
        // JPEG SOI marker
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xD8;
        // JPEG EOI marker
        data[data.length - 2] = (byte) 0xFF;
        data[data.length - 1] = (byte) 0xD9;
        return data;
    }

    private byte[] generateFakeVideoSegment(int width, int height) {
        // Generate fake MPEG-TS data
        int fakeSize = (width * height) / 10; // Rough estimate
        return new byte[fakeSize];
    }
}
