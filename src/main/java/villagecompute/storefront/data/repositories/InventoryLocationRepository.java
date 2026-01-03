package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for InventoryLocation entity with tenant-aware queries.
 *
 * <p>
 * Manages physical and virtual inventory locations, always scoped to the current tenant.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryLocationRepository implements PanacheRepositoryBase<InventoryLocation, UUID> {

    private static final String QUERY_FIND_BY_CODE = "tenant.id = :tenantId and code = :code";
    private static final String QUERY_FIND_ACTIVE = "tenant.id = :tenantId and active = true";
    private static final String QUERY_FIND_BY_TYPE = "tenant.id = :tenantId and type = :type";
    private static final String QUERY_FIND_BY_ID = "tenant.id = :tenantId and id = :id";

    /**
     * Find location by unique code within tenant.
     *
     * @param code
     *            location code
     * @return location if found
     */
    public Optional<InventoryLocation> findByCode(String code) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_CODE, Parameters.with("tenantId", tenantId).and("code", code)).firstResultOptional();
    }

    /**
     * Find all active locations for current tenant.
     *
     * @return list of active locations
     */
    public List<InventoryLocation> findActive() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_ACTIVE, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find all locations of a specific type.
     *
     * @param type
     *            location type (e.g., "warehouse", "retail")
     * @return list of locations
     */
    public List<InventoryLocation> findByType(String type) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TYPE, Parameters.with("tenantId", tenantId).and("type", type));
    }

    /**
     * Find all locations for current tenant.
     *
     * @return list of all locations
     */
    public List<InventoryLocation> findAllForTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id", tenantId);
    }

    /**
     * Find a location by ID scoped to the current tenant.
     *
     * @param id
     *            location UUID
     * @return optional location
     */
    public Optional<InventoryLocation> findByIdForTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_ID, Parameters.with("tenantId", tenantId).and("id", id)).firstResultOptional();
    }
}
