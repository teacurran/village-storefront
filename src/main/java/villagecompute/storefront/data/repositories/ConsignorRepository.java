package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for Consignor entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link Consignor}</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignorRepository implements PanacheRepositoryBase<Consignor, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_TENANT_AND_NAME = "tenant.id = :tenantId and lower(name) like :name";
    private static final String QUERY_COUNT_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";

    /**
     * Find all consignors for the current tenant.
     *
     * @return list of consignors
     */
    public List<Consignor> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find active consignors for the current tenant with pagination.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of active consignors
     */
    public List<Consignor> findActiveByCurrentTenant(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "active"))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find consignors by status for the current tenant with pagination.
     *
     * @param status
     *            consignor status
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of consignors
     */
    public List<Consignor> findByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Search consignors by name within current tenant.
     *
     * @param searchTerm
     *            search term (case-insensitive partial match)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of matching consignors
     */
    public List<Consignor> searchByName(String searchTerm, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String searchPattern = "%" + searchTerm.toLowerCase() + "%";
        return find(QUERY_FIND_BY_TENANT_AND_NAME, Parameters.with("tenantId", tenantId).and("name", searchPattern))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find consignor by ID within current tenant with ownership verification.
     *
     * @param id
     *            consignor UUID
     * @return consignor if found and belongs to current tenant
     */
    public Optional<Consignor> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Count consignors by status for the current tenant.
     *
     * @param status
     *            consignor status
     * @return count of consignors
     */
    public long countByStatus(String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_COUNT_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status));
    }

    /**
     * Count all active consignors for the current tenant.
     *
     * @return active consignor count
     */
    public long countActiveByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "active"));
    }
}
