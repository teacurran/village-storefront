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

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * MediaAccessLog entity tracking signed URL generation and access.
 *
 * <p>
 * Provides audit trail for digital product downloads and private media access. Tracks signature version for URL
 * invalidation and records access details (IP, user agent).
 *
 * <p>
 * Per Foundation §6.0: Digital products use signed URLs with 24-hour expiry and max 5 download attempts.
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260108__media_pipeline_tables.sql</li>
 * <li>Architecture §1.4: Signed URLs with 24-hour expiry</li>
 * <li>Foundation §6.0: Digital product download limits</li>
 * <li>Task: I4.T3 Media Pipeline</li>
 * </ul>
 */
@Entity
@Table(
        name = "media_access_logs")
public class MediaAccessLog extends PanacheEntityBase {

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

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "derivative_id")
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public MediaDerivative derivative;

    @Column(
            name = "signature_version",
            nullable = false)
    public Integer signatureVersion;

    @Column(
            name = "expires_at",
            nullable = false)
    public OffsetDateTime expiresAt;

    @Column(
            name = "accessed_at")
    public OffsetDateTime accessedAt;

    @Column(
            name = "access_ip",
            length = 64)
    public String accessIp;

    @Column(
            name = "user_agent",
            columnDefinition = "TEXT")
    public String userAgent;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        createdAt = OffsetDateTime.now();
    }

    /**
     * Check if URL has expired.
     */
    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }

    /**
     * Check if URL has been accessed.
     */
    public boolean hasBeenAccessed() {
        return accessedAt != null;
    }

    /**
     * Record access with IP and user agent.
     */
    public void recordAccess(String ip, String userAgent) {
        this.accessedAt = OffsetDateTime.now();
        this.accessIp = ip;
        this.userAgent = userAgent;
    }
}
