package villagecompute.storefront.data.repositories;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import villagecompute.storefront.data.models.MediaQuota;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for MediaQuota entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Provides methods for quota
 * management and usage tracking.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link MediaQuota}</li>
 * <li>Task I4.T3: Media Pipeline implementation</li>
 * </ul>
 */
@ApplicationScoped
public class MediaQuotaRepository implements PanacheRepositoryBase<MediaQuota, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";

    /**
     * Find media quota for the current tenant.
     *
     * @return media quota if exists
     */
    public Optional<MediaQuota> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId)).firstResultOptional();
    }

    /**
     * Find or create media quota for the current tenant.
     *
     * @return media quota (existing or newly created)
     */
    @Transactional
    public MediaQuota findOrCreateForCurrentTenant() {
        Optional<MediaQuota> existing = findByCurrentTenant();
        if (existing.isPresent()) {
            return existing.get();
        }

        // Create new quota with defaults
        UUID tenantId = TenantContext.getCurrentTenantId();
        Tenant tenant = Tenant.findById(tenantId);

        MediaQuota quota = new MediaQuota();
        quota.tenant = tenant;
        quota.quotaBytes = MediaQuota.DEFAULT_QUOTA_BYTES;
        quota.usedBytes = 0L;
        quota.warnThreshold = MediaQuota.DEFAULT_WARN_THRESHOLD;
        quota.enforceQuota = true;
        quota.notes = "Auto-created quota for tenant";

        persist(quota);
        return quota;
    }

    /**
     * Update quota usage (add or subtract bytes).
     *
     * @param deltaBytes
     *            bytes to add (positive) or subtract (negative)
     * @return updated quota
     */
    @Transactional
    public MediaQuota updateUsage(long deltaBytes) {
        MediaQuota quota = findOrCreateForCurrentTenant();
        if (deltaBytes > 0) {
            quota.addUsage(deltaBytes);
        } else if (deltaBytes < 0) {
            quota.removeUsage(Math.abs(deltaBytes));
        }
        persist(quota);
        return quota;
    }

    /**
     * Check if tenant has sufficient quota for an upload.
     *
     * @param requiredBytes
     *            bytes required for upload
     * @return true if quota available, false if quota would be exceeded
     */
    public boolean hasAvailableQuota(long requiredBytes) {
        MediaQuota quota = findOrCreateForCurrentTenant();
        if (!quota.enforceQuota) {
            return true; // Quota not enforced for this tenant
        }
        return !quota.wouldExceedQuota(requiredBytes);
    }

    /**
     * Get remaining quota for the current tenant.
     *
     * @return remaining bytes available
     */
    public long getRemainingQuota() {
        MediaQuota quota = findOrCreateForCurrentTenant();
        return quota.getRemainingBytes();
    }

    /**
     * Check if tenant has reached warning threshold.
     *
     * @return true if warning threshold reached
     */
    public boolean isWarningThresholdReached() {
        Optional<MediaQuota> quota = findByCurrentTenant();
        return quota.map(MediaQuota::isWarningThresholdReached).orElse(false);
    }
}
