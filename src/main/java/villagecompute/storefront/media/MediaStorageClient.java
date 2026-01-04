package villagecompute.storefront.media;

import java.io.InputStream;
import java.time.Duration;

/**
 * Abstraction for storing and retrieving media files from cloud object storage.
 *
 * <p>
 * Implementations provide integration with Cloudflare R2 (or compatible S3-based storage). The interface supports
 * upload, signed URL generation, and deletion with tenant-scoped object keys.
 *
 * <p>
 * Object Key Structure: {tenantId}/media/{assetType}/{assetId}/{filename}
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Architecture §1.4: Signed URLs with 24-hour expiry, hashed keys for cache-busting</li>
 * <li>Architecture §4.1.6: Cloudflare R2 multi-tenant isolation via prefix structure</li>
 * </ul>
 */
public interface MediaStorageClient {

    /**
     * Upload a media file to object storage.
     *
     * @param key
     *            object key (path) in storage with tenant prefix
     * @param data
     *            input stream containing media data
     * @param contentType
     *            MIME type of the media (e.g., "image/jpeg", "video/mp4")
     * @param contentLength
     *            size of the data in bytes
     * @return the object key of the uploaded file
     */
    String uploadMedia(String key, InputStream data, String contentType, long contentLength);

    /**
     * Generate a presigned upload URL for direct client uploads.
     *
     * @param key
     *            object key (path) in storage with tenant prefix
     * @param contentType
     *            expected MIME type
     * @param expiry
     *            duration until the URL expires
     * @return presigned upload URL with required headers
     */
    PresignedUploadUrl getPresignedUploadUrl(String key, String contentType, Duration expiry);

    /**
     * Generate a time-limited signed URL for downloading media.
     *
     * @param key
     *            object key of the media
     * @param expiry
     *            duration until the URL expires (default 24 hours per Architecture §1.4)
     * @return signed download URL
     */
    String getSignedDownloadUrl(String key, Duration expiry);

    /**
     * Download media file data.
     *
     * @param key
     *            object key to download
     * @return input stream of media data
     */
    InputStream downloadMedia(String key);

    /**
     * Delete a media file from storage.
     *
     * @param key
     *            object key to delete
     */
    void deleteMedia(String key);

    /**
     * Check if media exists in storage.
     *
     * @param key
     *            object key to check
     * @return true if the media exists
     */
    boolean mediaExists(String key);

    /**
     * Get object metadata (size, content type, etc.).
     *
     * @param key
     *            object key
     * @return metadata or null if not found
     */
    MediaObjectMetadata getMetadata(String key);

    /**
     * Presigned upload URL with required metadata.
     */
    class PresignedUploadUrl {
        private final String url;
        private final String objectKey;
        private final Duration expiry;

        public PresignedUploadUrl(String url, String objectKey, Duration expiry) {
            this.url = url;
            this.objectKey = objectKey;
            this.expiry = expiry;
        }

        public String getUrl() {
            return url;
        }

        public String getObjectKey() {
            return objectKey;
        }

        public Duration getExpiry() {
            return expiry;
        }
    }

    /**
     * Object metadata from storage.
     */
    class MediaObjectMetadata {
        private final String key;
        private final String contentType;
        private final long size;
        private final String etag;

        public MediaObjectMetadata(String key, String contentType, long size, String etag) {
            this.key = key;
            this.contentType = contentType;
            this.size = size;
            this.etag = etag;
        }

        public String getKey() {
            return key;
        }

        public String getContentType() {
            return contentType;
        }

        public long getSize() {
            return size;
        }

        public String getEtag() {
            return etag;
        }
    }
}
