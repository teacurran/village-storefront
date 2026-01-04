package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for ConsignmentItem entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link ConsignmentItem}</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentItemRepository implements PanacheRepositoryBase<ConsignmentItem, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_CONSIGNOR = "tenant.id = :tenantId and consignor.id = :consignorId";
    private static final String QUERY_FIND_BY_TENANT_AND_PRODUCT = "tenant.id = :tenantId and product.id = :productId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_SOLD_BY_CONSIGNOR = "tenant.id = :tenantId and consignor.id = :consignorId and status = :status";

    /**
     * Find all consignment items for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of items
     */
    public List<ConsignmentItem> findByConsignor(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_CONSIGNOR,
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId)).page(Page.of(page, size)).list();
    }

    /**
     * Find consignment items for a product.
     *
     * @param productId
     *            product UUID
     * @return list of consignment items
     */
    public List<ConsignmentItem> findByProduct(UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT_AND_PRODUCT,
                Parameters.with("tenantId", tenantId).and("productId", productId));
    }

    /**
     * Find consignment items by status.
     *
     * @param status
     *            item status
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of items
     */
    public List<ConsignmentItem> findByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find sold items for a consignor (for payout calculation).
     *
     * @param consignorId
     *            consignor UUID
     * @return list of sold items
     */
    public List<ConsignmentItem> findSoldByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_SOLD_BY_CONSIGNOR,
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId).and("status", "sold"));
    }

    /**
     * Find consignment item by ID within current tenant with ownership verification.
     *
     * @param id
     *            item UUID
     * @return item if found and belongs to current tenant
     */
    public Optional<ConsignmentItem> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Count active consignment items for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return count of active items
     */
    public long countActiveByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count("tenant.id = :tenantId and consignor.id = :consignorId and status = :status",
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId).and("status", "active"));
    }

    /**
     * Count sold items for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return count of sold items
     */
    public long countSoldByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_SOLD_BY_CONSIGNOR,
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId).and("status", "sold"));
    }
}
