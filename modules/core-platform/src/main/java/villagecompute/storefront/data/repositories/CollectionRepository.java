package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for Collection entity with tenant-aware queries.
 *
 * <p>
 * Provides methods for retrieving collections by various criteria, always scoped to the current tenant. Supports
 * pagination, filtering by status, and search capabilities.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link Collection}</li>
 * </ul>
 */
@ApplicationScoped
public class CollectionRepository implements PanacheRepositoryBase<Collection, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_TENANT_AND_CODE = "tenant.id = :tenantId and code = :code";
    private static final String QUERY_FIND_BY_TENANT_AND_SLUG = "tenant.id = :tenantId and slug = :slug";
    private static final String QUERY_FIND_PUBLISHED = "tenant.id = :tenantId and published = true and status = 'active' order by displayOrder";
    private static final String QUERY_SEARCH_BY_NAME = "tenant.id = :tenantId and status = :status and lower(name) like :search order by displayOrder";

    /**
     * Find all collections for the current tenant.
     *
     * @return list of collections
     */
    public List<Collection> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find collections for the current tenant with pagination support.
     *
     * @param page
     *            zero-indexed page
     * @param size
     *            page size
     * @return paged collections
     */
    public List<Collection> findByCurrentTenant(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId)).page(Page.of(page, size)).list();
    }

    /**
     * Find collections by status for the current tenant with pagination.
     *
     * @param status
     *            collection status (draft, active, archived, deleted)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of collections
     */
    public List<Collection> findByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find collection by code within current tenant.
     *
     * @param code
     *            collection code
     * @return collection if found
     */
    public Optional<Collection> findByCode(String code) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_CODE, Parameters.with("tenantId", tenantId).and("code", code))
                .firstResultOptional();
    }

    /**
     * Find collection by slug within current tenant.
     *
     * @param slug
     *            collection slug
     * @return collection if found
     */
    public Optional<Collection> findBySlug(String slug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_SLUG, Parameters.with("tenantId", tenantId).and("slug", slug))
                .firstResultOptional();
    }

    /**
     * Find published collections for storefront display.
     *
     * @return list of published active collections ordered by display order
     */
    public List<Collection> findPublished() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_PUBLISHED, Parameters.with("tenantId", tenantId));
    }

    /**
     * Search collections by name within current tenant.
     *
     * @param searchTerm
     *            search term (case-insensitive partial match)
     * @param status
     *            collection status filter
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of matching collections
     */
    public List<Collection> searchCollections(String searchTerm, String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String searchPattern = "%" + searchTerm.toLowerCase() + "%";
        return find(QUERY_SEARCH_BY_NAME,
                Parameters.with("tenantId", tenantId).and("status", status).and("search", searchPattern))
                .page(Page.of(page, size)).list();
    }

    /**
     * Count collections by status for the current tenant.
     *
     * @param status
     *            collection status
     * @return total count
     */
    public long countByStatus(String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status));
    }

    /**
     * Count all collections for the current tenant.
     *
     * @return total collection count
     */
    public long countByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find collection by ID ensuring it belongs to the current tenant.
     *
     * @param id
     *            collection UUID
     * @return collection if tenant owns it
     */
    public Optional<Collection> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }
}
