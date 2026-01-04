package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.data.models.TransferStatus;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for InventoryTransfer entity with tenant-aware queries.
 *
 * <p>
 * Manages inventory transfers between locations, always scoped to the current tenant.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryTransferRepository implements PanacheRepositoryBase<InventoryTransfer, UUID> {

    private static final String QUERY_FIND_BY_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_SOURCE = "tenant.id = :tenantId and sourceLocation.id = :locationId";
    private static final String QUERY_FIND_BY_DESTINATION = "tenant.id = :tenantId and destinationLocation.id = :locationId";
    private static final String QUERY_FIND_BY_ID = "tenant.id = :tenantId and id = :id";

    /**
     * Find all transfers with a specific status.
     *
     * @param status
     *            transfer status
     * @return list of transfers
     */
    public List<InventoryTransfer> findByStatus(TransferStatus status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_STATUS, Parameters.with("tenantId", tenantId).and("status", status));
    }

    /**
     * Find all transfers originating from a location.
     *
     * @param locationId
     *            source location UUID
     * @return list of transfers
     */
    public List<InventoryTransfer> findBySourceLocation(UUID locationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_SOURCE, Parameters.with("tenantId", tenantId).and("locationId", locationId));
    }

    /**
     * Find all transfers destined for a location.
     *
     * @param locationId
     *            destination location UUID
     * @return list of transfers
     */
    public List<InventoryTransfer> findByDestinationLocation(UUID locationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_DESTINATION, Parameters.with("tenantId", tenantId).and("locationId", locationId));
    }

    /**
     * Find all transfers for current tenant.
     *
     * @return list of all transfers
     */
    public List<InventoryTransfer> findAllForTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id", tenantId);
    }

    /**
     * Find a transfer by ID scoped to the current tenant.
     *
     * @param transferId
     *            transfer UUID
     * @return transfer if found
     */
    public Optional<InventoryTransfer> findByIdForTenant(UUID transferId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_ID, Parameters.with("tenantId", tenantId).and("id", transferId))
                .firstResultOptional();
    }
}
