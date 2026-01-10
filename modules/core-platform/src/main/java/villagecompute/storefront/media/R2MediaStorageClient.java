package villagecompute.storefront.media;

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
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * Cloudflare R2 implementation of MediaStorageClient using AWS S3 SDK.
 *
 * <p>
 * Integrates with R2 (S3-compatible) object storage for media file persistence. Requires R2 credentials and endpoint
 * configuration.
 *
 * <p>
 * To enable this implementation, add to application.properties:
 *
 * <pre>
 * quarkus.arc.selected-alternatives=villagecompute.storefront.media.R2MediaStorageClient
 * media.storage.r2.endpoint=https://&lt;account-id&gt;.r2.cloudflarestorage.com
 * media.storage.r2.bucket=village-media
 * media.storage.r2.access-key-id=&lt;key&gt;
 * media.storage.r2.secret-access-key=&lt;secret&gt;
 * media.storage.r2.region=auto
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T3 - Media Pipeline</li>
 * <li>Architecture §4.1.6: Cloudflare R2 multi-tenant isolation via prefix structure</li>
 * <li>Cloudflare R2 Docs: https://developers.cloudflare.com/r2/</li>
 * </ul>
 */
@ApplicationScoped
@Alternative
public class R2MediaStorageClient implements MediaStorageClient {

    private static final Logger LOG = Logger.getLogger(R2MediaStorageClient.class);

    @ConfigProperty(
            name = "media.storage.r2.endpoint")
    String r2Endpoint;

    @ConfigProperty(
            name = "media.storage.r2.bucket")
    String bucketName;

    @ConfigProperty(
            name = "media.storage.r2.access-key-id")
    String accessKeyId;

    @ConfigProperty(
            name = "media.storage.r2.secret-access-key")
    String secretAccessKey;

    @ConfigProperty(
            name = "media.storage.r2.region",
            defaultValue = "auto")
    String region;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    @PostConstruct
    void initialize() {
        LOG.infof("Initializing R2 storage client: endpoint=%s, bucket=%s, region=%s", r2Endpoint, bucketName, region);

        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);

        s3Client = S3Client.builder().endpointOverride(URI.create(r2Endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();

        s3Presigner = S3Presigner.builder().endpointOverride(URI.create(r2Endpoint)).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials)).build();

        LOG.info("R2 storage client initialized successfully");
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
    public String uploadMedia(String key, InputStream data, String contentType, long contentLength) {
        LOG.infof("R2 upload: key=%s, contentType=%s, contentLength=%d", key, contentType, contentLength);

        try {
            PutObjectRequest request = PutObjectRequest.builder().bucket(bucketName).key(key).contentType(contentType)
                    .contentLength(contentLength).build();

            s3Client.putObject(request, RequestBody.fromInputStream(data, contentLength));

            LOG.infof("R2 upload successful: key=%s", key);
            return key;
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 upload failed: key=%s, statusCode=%d", key, e.statusCode());
            throw new MediaStorageException("Failed to upload media to R2: " + key, e);
        }
    }

    @Override
    public PresignedUploadUrl getPresignedUploadUrl(String key, String contentType, Duration expiry) {
        LOG.infof("R2 presigned upload URL: key=%s, contentType=%s, expiry=%s", key, contentType, expiry);

        try {
            PutObjectRequest putRequest = PutObjectRequest.builder().bucket(bucketName).key(key)
                    .contentType(contentType).build();

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder().signatureDuration(expiry)
                    .putObjectRequest(putRequest).build();

            PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);

            LOG.infof("R2 presigned upload URL generated: key=%s, expires in %s", key, expiry);
            return new PresignedUploadUrl(presigned.url().toString(), key, expiry);
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 presigned URL generation failed: key=%s", key);
            throw new MediaStorageException("Failed to generate presigned upload URL: " + key, e);
        }
    }

    @Override
    public String getSignedDownloadUrl(String key, Duration expiry) {
        LOG.infof("R2 signed download URL: key=%s, expiry=%s", key, expiry);

        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder().bucket(bucketName).key(key).build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder().signatureDuration(expiry)
                    .getObjectRequest(getObjectRequest).build();

            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);

            LOG.infof("R2 signed download URL generated: key=%s, expires in %s", key, expiry);
            return presigned.url().toString();
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 signed download URL generation failed: key=%s", key);
            throw new MediaStorageException("Failed to generate signed download URL: " + key, e);
        }
    }

    @Override
    public InputStream downloadMedia(String key) {
        LOG.infof("R2 download: key=%s", key);

        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(bucketName).key(key).build();

            InputStream stream = s3Client.getObject(request);
            LOG.infof("R2 download successful: key=%s", key);
            return stream;
        } catch (NoSuchKeyException e) {
            LOG.warnf("R2 download failed - key not found: %s", key);
            throw new MediaNotFoundException("Media not found: " + key, e);
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 download failed: key=%s, statusCode=%d", key, e.statusCode());
            throw new MediaStorageException("Failed to download media from R2: " + key, e);
        }
    }

    @Override
    public void deleteMedia(String key) {
        LOG.infof("R2 delete: key=%s", key);

        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucketName).key(key).build();

            s3Client.deleteObject(request);
            LOG.infof("R2 delete successful: key=%s", key);
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 delete failed: key=%s, statusCode=%d", key, e.statusCode());
            throw new MediaStorageException("Failed to delete media from R2: " + key, e);
        }
    }

    @Override
    public boolean mediaExists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(key).build();

            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 exists check failed: key=%s, statusCode=%d", key, e.statusCode());
            throw new MediaStorageException("Failed to check media existence in R2: " + key, e);
        }
    }

    @Override
    public MediaObjectMetadata getMetadata(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder().bucket(bucketName).key(key).build();

            HeadObjectResponse response = s3Client.headObject(request);
            return new MediaObjectMetadata(key, response.contentType(), response.contentLength(), response.eTag());
        } catch (NoSuchKeyException e) {
            LOG.warnf("R2 metadata failed - key not found: %s", key);
            return null;
        } catch (S3Exception e) {
            LOG.errorf(e, "R2 metadata failed: key=%s, statusCode=%d", key, e.statusCode());
            throw new MediaStorageException("Failed to get media metadata from R2: " + key, e);
        }
    }
}
