package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.MediaDerivative;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for MediaDerivative entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Provides methods to retrieve
 * derivatives by asset, type, and storage key.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link MediaDerivative}</li>
 * <li>Task I4.T3: Media Pipeline implementation</li>
 * </ul>
 */
@ApplicationScoped
public class MediaDerivativeRepository implements PanacheRepositoryBase<MediaDerivative, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_ASSET = "tenant.id = :tenantId and asset.id = :assetId";
    private static final String QUERY_FIND_BY_ASSET_AND_TYPE = "tenant.id = :tenantId and asset.id = :assetId and derivativeType = :derivativeType";
    private static final String QUERY_FIND_BY_STORAGE_KEY = "tenant.id = :tenantId and storageKey = :storageKey";
    private static final String QUERY_DELETE_BY_ASSET = "asset.id = :assetId and tenant.id = :tenantId";

    /**
     * Find all derivatives for a given media asset.
     *
     * @param assetId
     *            media asset UUID
     * @return list of derivatives
     */
    public List<MediaDerivative> findByAsset(UUID assetId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_ASSET, Parameters.with("tenantId", tenantId).and("assetId", assetId));
    }

    /**
     * Find a specific derivative by asset and type.
     *
     * @param assetId
     *            media asset UUID
     * @param derivativeType
     *            derivative type ('thumbnail', 'small', 'hls_master', etc.)
     * @return derivative if found
     */
    public Optional<MediaDerivative> findByAssetAndType(UUID assetId, String derivativeType) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_ASSET_AND_TYPE,
                Parameters.with("tenantId", tenantId).and("assetId", assetId).and("derivativeType", derivativeType))
                .firstResultOptional();
    }

    /**
     * Find derivative by storage key within current tenant.
     *
     * @param storageKey
     *            object storage key
     * @return derivative if found
     */
    public Optional<MediaDerivative> findByStorageKey(String storageKey) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_STORAGE_KEY, Parameters.with("tenantId", tenantId).and("storageKey", storageKey))
                .firstResultOptional();
    }

    /**
     * Find derivative by ID within current tenant with ownership verification.
     *
     * @param id
     *            derivative UUID
     * @return derivative if found and belongs to current tenant
     */
    public Optional<MediaDerivative> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Delete all derivatives for a given asset.
     *
     * @param assetId
     *            media asset UUID
     * @return number of derivatives deleted
     */
    public long deleteByAsset(UUID assetId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return delete(QUERY_DELETE_BY_ASSET, Parameters.with("assetId", assetId).and("tenantId", tenantId));
    }

    /**
     * Count derivatives for a given asset.
     *
     * @param assetId
     *            media asset UUID
     * @return derivative count
     */
    public long countByAsset(UUID assetId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_ASSET, Parameters.with("tenantId", tenantId).and("assetId", assetId));
    }

    /**
     * Find all derivatives of a specific type across all assets.
     *
     * @param derivativeType
     *            derivative type
     * @return list of derivatives
     */
    public List<MediaDerivative> findByType(String derivativeType) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId and derivativeType = :derivativeType",
                Parameters.with("tenantId", tenantId).and("derivativeType", derivativeType));
    }
}
