package villagecompute.storefront.reporting;

import java.io.InputStream;
import java.time.Duration;

/**
 * Abstraction for storing and retrieving report files from cloud object storage.
 *
 * <p>
 * Implementations provide integration with Cloudflare R2 (or compatible S3-based storage). The interface supports
 * upload, signed URL generation, and deletion.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6 - Background Jobs & Reporting Exports)</li>
 * </ul>
 */
public interface ReportStorageClient {

    /**
     * Upload a report file to object storage.
     *
     * @param key
     *            object key (path) in storage
     * @param data
     *            input stream containing report data
     * @param contentType
     *            MIME type of the report (e.g., "text/csv", "application/pdf")
     * @param contentLength
     *            size of the data in bytes
     * @return the object key of the uploaded file
     */
    String uploadReport(String key, InputStream data, String contentType, long contentLength);

    /**
     * Generate a time-limited signed URL for downloading a report.
     *
     * @param key
     *            object key of the report
     * @param expiry
     *            duration until the URL expires
     * @return signed download URL
     */
    String getSignedDownloadUrl(String key, Duration expiry);

    /**
     * Delete a report file from storage.
     *
     * @param key
     *            object key to delete
     */
    void deleteReport(String key);

    /**
     * Check if a report exists in storage.
     *
     * @param key
     *            object key to check
     * @return true if the report exists
     */
    boolean reportExists(String key);
}
