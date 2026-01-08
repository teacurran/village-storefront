package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.MediaAccessLog;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for {@link MediaAccessLog} with tenant-aware helpers.
 */
@ApplicationScoped
public class MediaAccessLogRepository implements PanacheRepositoryBase<MediaAccessLog, UUID> {

    /**
     * Find access logs for a media asset.
     *
     * @param assetId
     *            media asset identifier
     * @return ordered list of logs (newest first)
     */
    public List<MediaAccessLog> findByAsset(UUID assetId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId and asset.id = :assetId ORDER BY createdAt DESC",
                Parameters.with("tenantId", tenantId).and("assetId", assetId));
    }

    /**
     * Delete logs for a specific asset.
     *
     * @param assetId
     *            media asset identifier
     * @return number of logs deleted
     */
    public long deleteByAsset(UUID assetId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return delete("tenant.id = :tenantId and asset.id = :assetId",
                Parameters.with("tenantId", tenantId).and("assetId", assetId));
    }

    /**
     * Prune expired signed URLs.
     *
     * @param expiryThreshold
     *            timestamp before which entries should be removed
     * @return number of logs removed
     */
    public long deleteExpired(OffsetDateTime expiryThreshold) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return delete("tenant.id = :tenantId and expiresAt < :threshold",
                Parameters.with("tenantId", tenantId).and("threshold", expiryThreshold));
    }
}
