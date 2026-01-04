package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * MediaQuota entity tracking storage quotas and usage per tenant.
 *
 * <p>
 * Enforces storage limits for uploaded media files (originals + derivatives). Quotas can be enforced strictly or used
 * as advisory thresholds for premium tenants.
 *
 * <p>
 * Default quota: 10GB (10,737,418,240 bytes) Warning threshold: 80% usage Enforcement: Enabled by default
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260108__media_pipeline_tables.sql</li>
 * <li>Architecture §1.4: PostgreSQL token buckets for throttling (no Redis)</li>
 * <li>Task: I4.T3 Media Pipeline</li>
 * </ul>
 */
@Entity
@Table(
        name = "media_quotas")
public class MediaQuota extends PanacheEntityBase {

    public static final long DEFAULT_QUOTA_BYTES = 10_737_418_240L; // 10GB

    public static final BigDecimal DEFAULT_WARN_THRESHOLD = new BigDecimal("0.80"); // 80%

    @Id
    @GeneratedValue
    public UUID id;

    @OneToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            unique = true)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    @Column(
            name = "quota_bytes",
            nullable = false)
    public Long quotaBytes = DEFAULT_QUOTA_BYTES;

    @Column(
            name = "used_bytes",
            nullable = false)
    public Long usedBytes = 0L;

    @Column(
            name = "warn_threshold",
            nullable = false,
            precision = 3,
            scale = 2)
    public BigDecimal warnThreshold = DEFAULT_WARN_THRESHOLD;

    @Column(
            name = "enforce_quota",
            nullable = false)
    public Boolean enforceQuota = true;

    @Column(
            columnDefinition = "TEXT")
    public String notes;

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
     * Check if quota has been exceeded.
     */
    public boolean isQuotaExceeded() {
        return usedBytes >= quotaBytes;
    }

    /**
     * Check if quota has reached warning threshold.
     */
    public boolean isWarningThresholdReached() {
        if (quotaBytes == 0)
            return false;
        double usageRatio = (double) usedBytes / quotaBytes;
        return usageRatio >= warnThreshold.doubleValue();
    }

    /**
     * Get remaining quota in bytes.
     */
    public long getRemainingBytes() {
        return Math.max(0, quotaBytes - usedBytes);
    }

    /**
     * Get usage percentage (0-100).
     */
    public double getUsagePercentage() {
        if (quotaBytes == 0)
            return 0.0;
        return ((double) usedBytes / quotaBytes) * 100.0;
    }

    /**
     * Check if upload of given size would exceed quota.
     */
    public boolean wouldExceedQuota(long additionalBytes) {
        return (usedBytes + additionalBytes) > quotaBytes;
    }

    /**
     * Add bytes to usage counter.
     */
    public void addUsage(long bytes) {
        this.usedBytes = (this.usedBytes == null ? 0L : this.usedBytes) + bytes;
    }

    /**
     * Subtract bytes from usage counter.
     */
    public void removeUsage(long bytes) {
        this.usedBytes = Math.max(0, (this.usedBytes == null ? 0L : this.usedBytes) - bytes);
    }
}
