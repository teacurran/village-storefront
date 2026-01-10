package villagecompute.storefront.backup;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Cloudflare R2 implementation of BackupStorageClient using AWS S3 SDK.
 *
 * <p>
 * Integrates with R2 (S3-compatible) object storage for database backup persistence. Provides checksum verification for
 * backup integrity and supports WAL archiving.
 *
 * <p>
 * To enable this implementation, add to application.properties:
 *
 * <pre>
 * quarkus.arc.selected-alternatives=villagecompute.storefront.backup.R2BackupStorageClient
 * backup.storage.r2.endpoint=https://&lt;account-id&gt;.r2.cloudflarestorage.com
 * backup.storage.r2.bucket=village-storefront-backups
 * backup.storage.r2.access-key-id=&lt;key&gt;
 * backup.storage.r2.secret-access-key=&lt;secret&gt;
 * backup.storage.r2.region=auto
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I6.T5 - Data Ops + DR Automation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.5)</li>
 * <li>Cloudflare R2 Docs: https://developers.cloudflare.com/r2/</li>
 * </ul>
 */
@ApplicationScoped
@Alternative
public class R2BackupStorageClient implements BackupStorageClient {

    private static final Logger LOG = Logger.getLogger(R2BackupStorageClient.class);

    @ConfigProperty(
            name = "backup.storage.r2.endpoint")
    String r2Endpoint;

    @ConfigProperty(
            name = "backup.storage.r2.bucket")
    String bucketName;

    @ConfigProperty(
            name = "backup.storage.r2.access-key-id")
    String accessKeyId;

    @ConfigProperty(
            name = "backup.storage.r2.secret-access-key")
    String secretAccessKey;

    @ConfigProperty(
            name = "backup.storage.r2.region",
            defaultValue = "auto")
    String region;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    @PostConstruct
    void initializeS3Client() {
        LOG.infof("Initializing R2 backup storage client - endpoint=%s, bucket=%s, region=%s", r2Endpoint, bucketName,
                region);

        try {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

            this.s3Client = S3Client.builder().region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(URI.create(r2Endpoint)).build();

            this.s3Presigner = S3Presigner.builder().region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(URI.create(r2Endpoint)).build();

            LOG.info("R2 backup storage client initialized successfully");
        } catch (Exception e) {
            LOG.error("Failed to initialize R2 backup storage client", e);
            throw new RuntimeException("R2 backup storage client initialization failed", e);
        }
    }

    @PreDestroy
    void cleanup() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (s3Presigner != null) {
            s3Presigner.close();
        }
        LOG.info("R2 backup storage client closed");
    }

    @Override
    public String uploadBackup(String key, InputStream data, String contentType, long contentLength,
            String md5Checksum) {
        LOG.infof("R2 backup upload: key=%s, contentType=%s, contentLength=%d, md5=%s", key, contentType, contentLength,
                md5Checksum);

        try {
            PutObjectRequest request = PutObjectRequest.builder().bucket(bucketName).key(key).contentType(contentType)
                    .contentLength(contentLength).contentMD5(md5Checksum) // R2 will verify checksum on upload
                    .build();

            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));

            LOG.infof("R2 backup upload completed - key=%s, eTag=%s", key, response.eTag());
            return key;
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup upload failed - key=%s", key);
            throw new RuntimeException("R2 backup upload failed for key: " + key, e);
        }
    }

    @Override
    public InputStream downloadBackup(String key) {
        LOG.infof("R2 backup download: key=%s", key);

        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(bucketName).key(key).build();

            return s3Client.getObject(request, ResponseTransformer.toInputStream());
        } catch (NoSuchKeyException e) {
            LOG.errorf("R2 backup not found - key=%s", key);
            throw new RuntimeException("R2 backup not found: " + key, e);
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup download failed - key=%s", key);
            throw new RuntimeException("R2 backup download failed for key: " + key, e);
        }
    }

    @Override
    public String getSignedDownloadUrl(String key, Duration expiry) {
        LOG.infof("R2 backup signed URL: key=%s, expiry=%s", key, expiry);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(key).build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder().signatureDuration(expiry)
                    .getObjectRequest(getObjectRequest).build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String signedUrl = presignedRequest.url().toString();

            LOG.infof("R2 backup signed URL generated - key=%s", key);
            return signedUrl;
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup signed URL generation failed - key=%s", key);
            throw new RuntimeException("R2 backup signed URL generation failed for key: " + key, e);
        }
    }

    @Override
    public void deleteBackup(String key) {
        LOG.infof("R2 backup delete: key=%s", key);

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();

            s3Client.deleteObject(request);

            LOG.infof("R2 backup delete completed - key=%s", key);
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup delete failed - key=%s", key);
            throw new RuntimeException("R2 backup delete failed for key: " + key, e);
        }
    }

    @Override
    public boolean backupExists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(key).build();

            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup exists check failed - key=%s", key);
            throw new RuntimeException("R2 backup exists check failed for key: " + key, e);
        }
    }

    @Override
    public boolean verifyChecksum(String key, String expectedMd5) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(key).build();

            HeadObjectResponse response = s3Client.headObject(request);

            // R2 returns ETag which is MD5 for single-part uploads
            String actualETag = response.eTag().replace("\"", "");

            boolean matches = actualETag.equalsIgnoreCase(expectedMd5);
            if (!matches) {
                LOG.warnf("Checksum mismatch for key=%s: expected=%s, actual=%s", key, expectedMd5, actualETag);
            }

            return matches;
        } catch (NoSuchKeyException e) {
            LOG.warnf("Cannot verify checksum - backup not found: key=%s", key);
            return false;
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup checksum verification failed - key=%s", key);
            throw new RuntimeException("R2 backup checksum verification failed for key: " + key, e);
        }
    }

    @Override
    public List<String> listBackups(String prefix) {
        LOG.infof("R2 backup list: prefix=%s", prefix);

        try {
            ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucketName).prefix(prefix).build();

            List<String> keys = new ArrayList<>();
            s3Client.listObjectsV2Paginator(request)
                    .forEach(response -> response.contents().stream().map(S3Object::key).forEach(keys::add));

            LOG.infof("R2 backup list completed - prefix=%s, count=%d", prefix, keys.size());
            return keys;
        } catch (Exception e) {
            LOG.errorf(e, "R2 backup list failed - prefix=%s", prefix);
            throw new RuntimeException("R2 backup list failed for prefix: " + prefix, e);
        }
    }
}
