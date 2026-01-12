package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.IntakeBatch;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for IntakeBatch entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Batch intake implementation</li>
 * <li>Entity: {@link IntakeBatch}</li>
 * </ul>
 */
@ApplicationScoped
public class IntakeBatchRepository implements PanacheRepositoryBase<IntakeBatch, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_CONSIGNOR = "consignor.id = :consignorId and tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";

    /**
     * Find intake batch by ID within current tenant.
     *
     * @param id
     *            batch UUID
     * @return batch if found and belongs to current tenant
     */
    public Optional<IntakeBatch> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Find intake batches for a consignor with pagination.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of batches
     */
    public List<IntakeBatch> findByConsignor(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_CONSIGNOR, Parameters.with("consignorId", consignorId).and("tenantId", tenantId))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find intake batches by status for the current tenant.
     *
     * @param status
     *            batch status
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of batches
     */
    public List<IntakeBatch> findByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Count batches for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return batch count
     */
    public long countByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_CONSIGNOR, Parameters.with("consignorId", consignorId).and("tenantId", tenantId));
    }
}
