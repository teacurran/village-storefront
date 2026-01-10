package villagecompute.storefront.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.arc.DefaultBean;

/**
 * In-memory stub implementation of MediaStorageClient for testing and development.
 *
 * <p>
 * Stores media in a concurrent hash map and generates fake signed URLs. Useful for local development and integration
 * tests where actual R2 access is not required.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Testing: MediaPipelineTest uses this stub to verify processing logic</li>
 * </ul>
 */
@ApplicationScoped
@DefaultBean
public class StubMediaStorageClient implements MediaStorageClient {

    private static final Logger LOG = Logger.getLogger(StubMediaStorageClient.class);

    private final Map<String, StoredMedia> storage = new ConcurrentHashMap<>();

    @Override
    public String uploadMedia(String key, InputStream data, String contentType, long contentLength) {
        LOG.infof("Stub upload: key=%s, contentType=%s, contentLength=%d", key, contentType, contentLength);

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            data.transferTo(buffer);
            byte[] bytes = buffer.toByteArray();
            storage.put(key, new StoredMedia(bytes, contentType));
            LOG.infof("Stored media with key: %s (%d bytes)", key, bytes.length);
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read media data for stub upload", e);
        }
    }

    @Override
    public PresignedUploadUrl getPresignedUploadUrl(String key, String contentType, Duration expiry) {
        LOG.infof("Stub presigned upload URL: key=%s, contentType=%s, expiry=%s", key, contentType, expiry);
        String url = String.format("https://stub-media.example.com/upload/%s?expires=%d", key, expiry.toSeconds());
        return new PresignedUploadUrl(url, key, expiry);
    }

    @Override
    public String getSignedDownloadUrl(String key, Duration expiry) {
        LOG.infof("Stub signed download URL: key=%s, expiry=%s", key, expiry);
        return String.format("https://stub-media.example.com/media/%s?expires=%d", key, expiry.toSeconds());
    }

    @Override
    public InputStream downloadMedia(String key) {
        LOG.infof("Stub download: key=%s", key);
        StoredMedia media = storage.get(key);
        if (media == null) {
            throw new RuntimeException("Media not found: " + key);
        }
        return new ByteArrayInputStream(media.getData());
    }

    @Override
    public void deleteMedia(String key) {
        LOG.infof("Stub delete: key=%s", key);
        storage.remove(key);
    }

    @Override
    public boolean mediaExists(String key) {
        return storage.containsKey(key);
    }

    @Override
    public MediaObjectMetadata getMetadata(String key) {
        StoredMedia media = storage.get(key);
        if (media == null) {
            return null;
        }
        String etag = String.valueOf(media.getData().length); // Fake ETag
        return new MediaObjectMetadata(key, media.getContentType(), media.getData().length, etag);
    }

    /**
     * Get stored media data (for test assertions).
     *
     * @param key
     *            object key
     * @return stored media or null if not found
     */
    public StoredMedia getStoredMedia(String key) {
        return storage.get(key);
    }

    /**
     * Clear all stored media (for test cleanup).
     */
    public void clear() {
        storage.clear();
    }

    /**
     * Record of stored media.
     */
    public static final class StoredMedia {

        private final byte[] data;
        private final String contentType;

        public StoredMedia(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }

        public byte[] getData() {
            return data;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
