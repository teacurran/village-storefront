package villagecompute.storefront.reporting;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

/**
 * Cloudflare R2 implementation of ReportStorageClient using AWS S3 SDK.
 *
 * <p>
 * Integrates with R2 (S3-compatible) object storage for report file persistence. Requires R2 credentials and endpoint
 * configuration.
 *
 * <p>
 * To enable this implementation, add to application.properties:
 *
 * <pre>
 * quarkus.arc.selected-alternatives=villagecompute.storefront.reporting.R2ReportStorageClient
 * reporting.storage.r2.endpoint=https://&lt;account-id&gt;.r2.cloudflarestorage.com
 * reporting.storage.r2.bucket=village-reports
 * reporting.storage.r2.access-key-id=&lt;key&gt;
 * reporting.storage.r2.secret-access-key=&lt;secret&gt;
 * reporting.storage.r2.region=auto
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I5.T2 - Reporting & Retention pipeline (R2 export integration)</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * <li>Cloudflare R2 Docs: https://developers.cloudflare.com/r2/</li>
 * </ul>
 */
@ApplicationScoped
@Alternative
public class R2ReportStorageClient implements ReportStorageClient {

    private static final Logger LOG = Logger.getLogger(R2ReportStorageClient.class);

    @ConfigProperty(
            name = "reporting.storage.r2.endpoint")
    String r2Endpoint;

    @ConfigProperty(
            name = "reporting.storage.r2.bucket")
    String bucketName;

    @ConfigProperty(
            name = "reporting.storage.r2.access-key-id")
    String accessKeyId;

    @ConfigProperty(
            name = "reporting.storage.r2.secret-access-key")
    String secretAccessKey;

    @ConfigProperty(
            name = "reporting.storage.r2.region",
            defaultValue = "auto")
    String region;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    @PostConstruct
    void initializeS3Client() {
        LOG.infof("Initializing R2 storage client - endpoint=%s, bucket=%s, region=%s", r2Endpoint, bucketName, region);

        try {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

            this.s3Client = S3Client.builder().region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(URI.create(r2Endpoint)).build();

            this.s3Presigner = S3Presigner.builder().region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .endpointOverride(URI.create(r2Endpoint)).build();

            LOG.info("R2 storage client initialized successfully");
        } catch (Exception e) {
            LOG.error("Failed to initialize R2 storage client", e);
            throw new RuntimeException("R2 storage client initialization failed", e);
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
        LOG.info("R2 storage client closed");
    }

    @Override
    public String uploadReport(String key, InputStream data, String contentType, long contentLength) {
        LOG.infof("R2 upload: key=%s, contentType=%s, contentLength=%d", key, contentType, contentLength);

        try {
            PutObjectRequest request = PutObjectRequest.builder().bucket(bucketName).key(key).contentType(contentType)
                    .contentLength(contentLength).build();

            PutObjectResponse response = s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));

            LOG.infof("R2 upload completed - key=%s, eTag=%s", key, response.eTag());
            return key;
        } catch (Exception e) {
            LOG.errorf(e, "R2 upload failed - key=%s", key);
            throw new RuntimeException("R2 upload failed for key: " + key, e);
        }
    }

    @Override
    public String getSignedDownloadUrl(String key, Duration expiry) {
        LOG.infof("R2 signed URL: key=%s, expiry=%s", key, expiry);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(key).build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder().signatureDuration(expiry)
                    .getObjectRequest(getObjectRequest).build();

            PresignedGetObjectRequest presignedRequest = s3Presigner.presignGetObject(presignRequest);
            String signedUrl = presignedRequest.url().toString();

            LOG.infof("R2 signed URL generated - key=%s, url=%s", key, signedUrl);
            return signedUrl;
        } catch (Exception e) {
            LOG.errorf(e, "R2 signed URL generation failed - key=%s", key);
            throw new RuntimeException("R2 signed URL generation failed for key: " + key, e);
        }
    }

    @Override
    public void deleteReport(String key) {
        LOG.infof("R2 delete: key=%s", key);

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();

            s3Client.deleteObject(request);

            LOG.infof("R2 delete completed - key=%s", key);
        } catch (Exception e) {
            LOG.errorf(e, "R2 delete failed - key=%s", key);
            throw new RuntimeException("R2 delete failed for key: " + key, e);
        }
    }

    @Override
    public boolean reportExists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(key).build();

            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            LOG.errorf(e, "R2 exists check failed - key=%s", key);
            throw new RuntimeException("R2 exists check failed for key: " + key, e);
        }
    }
}
