package villagecompute.storefront.backup;

import java.io.InputStream;
import java.time.Duration;

/**
 * Abstraction for storing and retrieving database backup files from cloud object storage.
 *
 * <p>
 * Implementations provide integration with Cloudflare R2 (or compatible S3-based storage) for disaster recovery
 * backups. The interface supports upload with checksum verification, download, and cleanup operations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I6.T5 - Data Ops + DR Automation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.5 - Data Operations & Backup Strategy)</li>
 * <li>Playbook: docs/operations/dr_playbook.md</li>
 * </ul>
 */
public interface BackupStorageClient {

    /**
     * Upload a backup file to object storage with checksum verification.
     *
     * @param key
     *            object key (path) in storage (e.g., "postgres/daily/backup-2026-01-10.tar.gz")
     * @param data
     *            input stream containing backup data
     * @param contentType
     *            MIME type of the backup (e.g., "application/gzip")
     * @param contentLength
     *            size of the data in bytes
     * @param md5Checksum
     *            MD5 checksum of the backup for integrity verification
     * @return the object key of the uploaded file
     */
    String uploadBackup(String key, InputStream data, String contentType, long contentLength, String md5Checksum);

    /**
     * Download a backup file from object storage.
     *
     * @param key
     *            object key of the backup
     * @return input stream containing backup data
     */
    InputStream downloadBackup(String key);

    /**
     * Generate a time-limited signed URL for downloading a backup (for external restore tools).
     *
     * @param key
     *            object key of the backup
     * @param expiry
     *            duration until the URL expires
     * @return signed download URL
     */
    String getSignedDownloadUrl(String key, Duration expiry);

    /**
     * Delete a backup file from storage (used during retention cleanup).
     *
     * @param key
     *            object key to delete
     */
    void deleteBackup(String key);

    /**
     * Check if a backup exists in storage.
     *
     * @param key
     *            object key to check
     * @return true if the backup exists
     */
    boolean backupExists(String key);

    /**
     * Verify backup integrity by comparing stored checksum with expected checksum.
     *
     * @param key
     *            object key of the backup
     * @param expectedMd5
     *            expected MD5 checksum
     * @return true if checksums match
     */
    boolean verifyChecksum(String key, String expectedMd5);

    /**
     * List backup files with a given prefix (used for retention cleanup).
     *
     * @param prefix
     *            object key prefix (e.g., "postgres/daily/")
     * @return list of backup keys matching the prefix
     */
    java.util.List<String> listBackups(String prefix);
}
