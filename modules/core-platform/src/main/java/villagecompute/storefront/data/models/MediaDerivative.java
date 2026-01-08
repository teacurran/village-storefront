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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * MediaDerivative entity representing processed variants of media assets.
 *
 * <p>
 * Stores metadata for derivative media files generated from original assets. Examples include image thumbnails, resized
 * images, HLS video segments, and video poster frames.
 *
 * <p>
 * Image derivative types:
 * <ul>
 * <li>thumbnail: Small preview (typically 150px)</li>
 * <li>small: Mobile-optimized (typically 400px)</li>
 * <li>medium: Tablet-optimized (typically 800px)</li>
 * <li>large: Desktop-optimized (typically 1600px)</li>
 * </ul>
 *
 * <p>
 * Video derivative types:
 * <ul>
 * <li>hls_master: HLS master playlist (m3u8)</li>
 * <li>hls_720p, hls_480p, hls_360p: HLS variant streams</li>
 * <li>poster: Static preview frame</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260108__media_pipeline_tables.sql</li>
 * <li>Architecture §1.4: Content hash for cache-busting</li>
 * <li>Task: I4.T3 Media Pipeline</li>
 * </ul>
 */
@Entity
@Table(
        name = "media_derivatives")
public class MediaDerivative extends PanacheEntityBase {

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

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "asset_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public MediaAsset asset;

    @Column(
            name = "derivative_type",
            nullable = false,
            length = 50)
    public String derivativeType; // 'thumbnail', 'small', 'medium', 'large', 'hls_master', etc.

    @Column(
            name = "storage_key",
            nullable = false,
            length = 500)
    public String storageKey; // R2/S3 object key

    @Column(
            name = "mime_type",
            nullable = false,
            length = 100)
    public String mimeType;

    @Column(
            name = "file_size",
            nullable = false)
    public Long fileSize;

    @Column
    public Integer width;

    @Column
    public Integer height;

    @Column(
            name = "duration_seconds")
    public Integer durationSeconds; // For video segments

    @Column(
            name = "content_hash",
            length = 64)
    public String contentHash; // Hash for CDN cache-busting

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String metadata = "{}"; // Derivative-specific metadata

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Version
    public Long version;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        createdAt = OffsetDateTime.now();
    }

    /**
     * Check if this is an image derivative.
     */
    public boolean isImageDerivative() {
        return derivativeType != null && (derivativeType.equals("thumbnail") || derivativeType.equals("small")
                || derivativeType.equals("medium") || derivativeType.equals("large"));
    }

    /**
     * Check if this is a video derivative.
     */
    public boolean isVideoDerivative() {
        return derivativeType != null && derivativeType.startsWith("hls_");
    }

    /**
     * Check if this is a video poster frame.
     */
    public boolean isPoster() {
        return "poster".equals(derivativeType);
    }
}
