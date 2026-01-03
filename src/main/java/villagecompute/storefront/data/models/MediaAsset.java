package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * MediaAsset entity representing original uploaded media files.
 *
 * <p>
 * Stores metadata for images and videos uploaded to object storage (Cloudflare R2). Tracks processing status,
 * dimensions, checksums, and download limits for digital products.
 *
 * <p>
 * Processing lifecycle:
 * <ul>
 * <li>UPLOADING: Client negotiating/uploading original object</li>
 * <li>PENDING: Upload complete, awaiting processing</li>
 * <li>PROCESSING: Derivatives currently being generated via background jobs</li>
 * <li>READY: All derivatives available for serving</li>
 * <li>FAILED: Processing encountered an error</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260108__media_pipeline_tables.sql</li>
 * <li>Architecture §1.4: Signed URLs with 24-hour expiry</li>
 * <li>Foundation §6.0: Digital products max 5 download attempts</li>
 * <li>Task: I4.T3 Media Pipeline</li>
 * </ul>
 */
@Entity
@Table(
        name = "media_assets")
public class MediaAsset extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    @Column(
            name = "asset_type",
            nullable = false,
            length = 20)
    public String assetType; // 'image', 'video'

    @Column(
            name = "original_filename",
            nullable = false,
            length = 255)
    public String originalFilename;

    @Column(
            name = "storage_key",
            nullable = false,
            length = 500)
    public String storageKey; // R2/S3 object key with tenant prefix

    @Column(
            name = "mime_type",
            nullable = false,
            length = 100)
    public String mimeType;

    @Column(
            name = "file_size",
            nullable = false)
    public Long fileSize;

    @Column(
            name = "checksum_sha256",
            length = 64)
    public String checksumSha256;

    @Column
    public Integer width;

    @Column
    public Integer height;

    @Column(
            name = "duration_seconds")
    public Integer durationSeconds; // For video only

    @Column(
            nullable = false,
            length = 20)
    public String status = "uploading"; // 'uploading', 'pending', 'processing', 'ready', 'failed'

    @Column(
            name = "processing_error",
            columnDefinition = "TEXT")
    public String processingError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String metadata = "{}"; // Additional metadata (EXIF, codec info, etc.)

    @Column(
            name = "uploaded_by")
    public UUID uploadedBy;

    @Column(
            name = "download_attempts",
            nullable = false)
    public Integer downloadAttempts = 0;

    @Column(
            name = "signature_version",
            nullable = false)
    public Integer signatureVersion = 1;

    @Column(
            name = "max_download_attempts")
    public Integer maxDownloadAttempts; // Null = unlimited

    @Column(
            name = "original_usage_tracked",
            nullable = false)
    public Boolean originalUsageTracked = Boolean.FALSE;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @Version
    public Long version;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Check if asset is ready for serving.
     */
    public boolean isReady() {
        return "ready".equals(status);
    }

    /**
     * Check if asset has failed processing.
     */
    public boolean isFailed() {
        return "failed".equals(status);
    }

    /**
     * Check if asset is currently being processed.
     */
    public boolean isProcessing() {
        return "processing".equals(status);
    }

    /**
     * Check if download limit has been reached.
     */
    public boolean isDownloadLimitReached() {
        return maxDownloadAttempts != null && downloadAttempts >= maxDownloadAttempts;
    }

    /**
     * Increment download attempt counter.
     */
    public void incrementDownloadAttempts() {
        this.downloadAttempts = (this.downloadAttempts == null ? 0 : this.downloadAttempts) + 1;
    }
}
