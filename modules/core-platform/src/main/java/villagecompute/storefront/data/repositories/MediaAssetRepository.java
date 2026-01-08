package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for MediaAsset entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link MediaAsset}</li>
 * <li>Task I4.T3: Media Pipeline implementation</li>
 * </ul>
 */
@ApplicationScoped
public class MediaAssetRepository implements PanacheRepositoryBase<MediaAsset, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_TENANT_AND_TYPE = "tenant.id = :tenantId and assetType = :assetType";
    private static final String QUERY_FIND_BY_STORAGE_KEY = "tenant.id = :tenantId and storageKey = :storageKey";
    private static final String QUERY_COUNT_BY_STATUS = "tenant.id = :tenantId and status = :status";

    /**
     * Find all media assets for the current tenant.
     *
     * @return list of media assets
     */
    public List<MediaAsset> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find media assets by status for the current tenant with pagination.
     *
     * @param status
     *            asset status ('pending', 'processing', 'ready', 'failed')
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of media assets
     */
    public List<MediaAsset> findByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find media assets by type for the current tenant.
     *
     * @param assetType
     *            asset type ('image', 'video')
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of media assets
     */
    public List<MediaAsset> findByType(String assetType, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_TYPE, Parameters.with("tenantId", tenantId).and("assetType", assetType))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find media asset by ID within current tenant with ownership verification.
     *
     * @param id
     *            media asset UUID
     * @return media asset if found and belongs to current tenant
     */
    public Optional<MediaAsset> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Find media asset by storage key within current tenant.
     *
     * @param storageKey
     *            object storage key
     * @return media asset if found
     */
    public Optional<MediaAsset> findByStorageKey(String storageKey) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_STORAGE_KEY, Parameters.with("tenantId", tenantId).and("storageKey", storageKey))
                .firstResultOptional();
    }

    /**
     * Find all pending assets for processing queue.
     *
     * @param limit
     *            maximum number of assets to return
     * @return list of pending assets
     */
    public List<MediaAsset> findPendingAssets(int limit) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "pending"))
                .page(Page.ofSize(limit)).list();
    }

    /**
     * Find all failed assets for retry or manual review.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of failed assets
     */
    public List<MediaAsset> findFailedAssets(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "failed"))
                .page(Page.of(page, size)).list();
    }

    /**
     * Count media assets by status for the current tenant.
     *
     * @param status
     *            asset status
     * @return count of assets
     */
    public long countByStatus(String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_COUNT_BY_STATUS, Parameters.with("tenantId", tenantId).and("status", status));
    }

    /**
     * Count all media assets for the current tenant.
     *
     * @return total asset count
     */
    public long countByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Search assets by optional type/status filters with tenant scoping.
     *
     * @param assetType
     *            optional type filter
     * @param status
     *            optional status filter
     * @return panache query for pagination
     */
    public PanacheQuery<MediaAsset> search(String assetType, String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        StringBuilder query = new StringBuilder("tenant.id = :tenantId");
        Parameters params = Parameters.with("tenantId", tenantId);

        if (assetType != null && !assetType.isBlank()) {
            query.append(" and assetType = :assetType");
            params = params.and("assetType", assetType);
        }

        if (status != null && !status.isBlank()) {
            query.append(" and status = :status");
            params = params.and("status", status);
        }

        query.append(" ORDER BY createdAt DESC");
        return find(query.toString(), params);
    }

    /**
     * Flush the persistence context (exposed for services that need generated IDs immediately).
     */
    public void flush() {
        getEntityManager().flush();
    }
}
