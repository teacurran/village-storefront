package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for Category entity with tenant-aware queries.
 *
 * <p>
 * Provides methods for retrieving categories hierarchically and by various identifiers, always scoped to the current
 * tenant.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link Category}</li>
 * </ul>
 */
@ApplicationScoped
public class CategoryRepository implements PanacheRepositoryBase<Category, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId and status = 'active'";
    private static final String QUERY_FIND_BY_TENANT_AND_CODE = "tenant.id = :tenantId and code = :code";
    private static final String QUERY_FIND_BY_TENANT_AND_SLUG = "tenant.id = :tenantId and slug = :slug";
    private static final String QUERY_FIND_ROOT_CATEGORIES = "tenant.id = :tenantId and parent is null and status = 'active' order by displayOrder";
    private static final String QUERY_FIND_BY_PARENT = "tenant.id = :tenantId and parent.id = :parentId and status = 'active' order by displayOrder";

    /**
     * Find all active categories for the current tenant.
     *
     * @return list of categories
     */
    public List<Category> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find category by code within current tenant.
     *
     * @param code
     *            category code
     * @return category if found
     */
    public Optional<Category> findByCode(String code) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_CODE, Parameters.with("tenantId", tenantId).and("code", code))
                .firstResultOptional();
    }

    /**
     * Find category by slug within current tenant.
     *
     * @param slug
     *            category slug
     * @return category if found
     */
    public Optional<Category> findBySlug(String slug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_SLUG, Parameters.with("tenantId", tenantId).and("slug", slug))
                .firstResultOptional();
    }

    /**
     * Find root categories (no parent) for the current tenant.
     *
     * @return list of root categories ordered by display order
     */
    public List<Category> findRootCategories() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_ROOT_CATEGORIES, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find child categories of a parent category.
     *
     * @param parentId
     *            parent category ID
     * @return list of child categories ordered by display order
     */
    public List<Category> findByParent(UUID parentId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_PARENT, Parameters.with("tenantId", tenantId).and("parentId", parentId));
    }
}
