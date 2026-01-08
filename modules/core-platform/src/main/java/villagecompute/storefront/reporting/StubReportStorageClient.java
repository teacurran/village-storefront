package villagecompute.storefront.reporting;

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
 * In-memory stub implementation of ReportStorageClient for testing and development.
 *
 * <p>
 * Stores reports in a concurrent hash map and generates fake signed URLs. Useful for local development and integration
 * tests where actual R2 access is not required.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Testing: ReportExportIT uses this stub to verify export logic</li>
 * </ul>
 */
@ApplicationScoped
@DefaultBean
public class StubReportStorageClient implements ReportStorageClient {

    private static final Logger LOG = Logger.getLogger(StubReportStorageClient.class);

    private final Map<String, StoredReport> storage = new ConcurrentHashMap<>();

    @Override
    public String uploadReport(String key, InputStream data, String contentType, long contentLength) {
        LOG.infof("Stub upload: key=%s, contentType=%s, contentLength=%d", key, contentType, contentLength);

        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            data.transferTo(buffer);
            byte[] bytes = buffer.toByteArray();
            storage.put(key, new StoredReport(bytes, contentType));
            LOG.infof("Stored report with key: %s (%d bytes)", key, bytes.length);
            return key;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read report data for stub upload", e);
        }
    }

    @Override
    public String getSignedDownloadUrl(String key, Duration expiry) {
        LOG.infof("Stub signed URL: key=%s, expiry=%s", key, expiry);
        // Generate fake signed URL for testing
        return String.format("https://stub-storage.example.com/reports/%s?expires=%d", key, expiry.toSeconds());
    }

    @Override
    public void deleteReport(String key) {
        LOG.infof("Stub delete: key=%s", key);
        storage.remove(key);
    }

    @Override
    public boolean reportExists(String key) {
        return storage.containsKey(key);
    }

    /**
     * Get stored report data (for test assertions).
     *
     * @param key
     *            object key
     * @return stored report or null if not found
     */
    public StoredReport getStoredReport(String key) {
        return storage.get(key);
    }

    /**
     * Clear all stored reports (for test cleanup).
     */
    public void clear() {
        storage.clear();
    }

    /**
     * Record of a stored report.
     */
    public static final class StoredReport {

        private final byte[] data;
        private final String contentType;

        public StoredReport(byte[] data, String contentType) {
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
