package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for Product entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link Product}</li>
 * </ul>
 */
@ApplicationScoped
public class ProductRepository implements PanacheRepositoryBase<Product, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_TENANT_AND_SKU = "tenant.id = :tenantId and sku = :sku";
    private static final String QUERY_FIND_BY_TENANT_AND_SLUG = "tenant.id = :tenantId and slug = :slug";
    private static final String QUERY_SEARCH_BY_NAME_OR_SKU = "tenant.id = :tenantId and status = :status and (lower(name) like :search or lower(sku) like :search)";
    private static final String QUERY_SEARCH_BY_TITLE = "tenant.id = :tenantId and status = :status and lower(title) like :search";
    private static final String QUERY_ACTIVE_BY_CATEGORY_SLUG = "tenant.id = :tenantId and status = 'active' and id in (select pc.product.id from ProductCategory pc where pc.id.tenantId = :tenantId and pc.category.slug = :categorySlug)";
    private static final String QUERY_ACTIVE_BY_COLLECTION_SLUG = "tenant.id = :tenantId and status = 'active' and id in (select pcl.product.id from ProductCollection pcl where pcl.id.tenantId = :tenantId and pcl.collection.slug = :collectionSlug)";
    private static final String QUERY_ACTIVE_BY_CATEGORY_AND_COLLECTION = QUERY_ACTIVE_BY_CATEGORY_SLUG
            + " and id in (select pcl.product.id from ProductCollection pcl where pcl.id.tenantId = :tenantId and pcl.collection.slug = :collectionSlug)";

    /**
     * Find all products for the current tenant.
     *
     * @return list of products
     */
    public List<Product> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Find products for the current tenant with pagination.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of products for requested page
     */
    public List<Product> findByCurrentTenant(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId)).page(Page.of(page, size)).list();
    }

    /**
     * Find active products for the current tenant with pagination.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of active products
     */
    public List<Product> findActiveByCurrentTenant(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "active"))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find products by status for the current tenant with pagination.
     *
     * @param status
     *            product status
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of products
     */
    public List<Product> findByStatusForCurrentTenant(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find product by SKU within current tenant.
     *
     * @param sku
     *            product SKU
     * @return product if found
     */
    public Optional<Product> findBySku(String sku) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_SKU, Parameters.with("tenantId", tenantId).and("sku", sku))
                .firstResultOptional();
    }

    /**
     * Find product by slug within current tenant.
     *
     * @param slug
     *            product slug
     * @return product if found
     */
    public Optional<Product> findBySlug(String slug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_SLUG, Parameters.with("tenantId", tenantId).and("slug", slug))
                .firstResultOptional();
    }

    /**
     * Search products by name or SKU within current tenant (legacy).
     *
     * @param searchTerm
     *            search term (case-insensitive partial match)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of matching active products
     */
    public List<Product> searchProductsByNameOrSku(String searchTerm, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String searchPattern = "%" + searchTerm.toLowerCase() + "%";
        return find(QUERY_SEARCH_BY_NAME_OR_SKU,
                Parameters.with("tenantId", tenantId).and("status", "active").and("search", searchPattern))
                .page(Page.of(page, size)).list();
    }

    /**
     * Search products by title within current tenant.
     *
     * @param searchTerm
     *            search term (case-insensitive partial match)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of matching active products
     */
    public List<Product> searchProducts(String searchTerm, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String searchPattern = "%" + searchTerm.toLowerCase() + "%";
        return find(QUERY_SEARCH_BY_TITLE,
                Parameters.with("tenantId", tenantId).and("status", "active").and("search", searchPattern))
                .page(Page.of(page, size)).list();
    }

    /**
     * Count search results for current tenant.
     *
     * @param searchTerm
     *            search term
     * @return total matches
     */
    public long countSearchResults(String searchTerm) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String searchPattern = "%" + searchTerm.toLowerCase() + "%";
        return count(QUERY_SEARCH_BY_TITLE,
                Parameters.with("tenantId", tenantId).and("status", "active").and("search", searchPattern));
    }

    /**
     * Count all products for the current tenant.
     *
     * @return total product count
     */
    public long countByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }

    /**
     * Count active products for the current tenant.
     *
     * @return active product count
     */
    public long countActiveByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "active"));
    }

    /**
     * Find product by ID ensuring it belongs to the current tenant.
     *
     * @param id
     *            product UUID
     * @return product if tenant owns it
     */
    public Optional<Product> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Find products assigned to a category slug.
     */
    public List<Product> findActiveByCategorySlug(String categorySlug, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_ACTIVE_BY_CATEGORY_SLUG,
                Parameters.with("tenantId", tenantId).and("categorySlug", categorySlug)).page(Page.of(page, size))
                .list();
    }

    /**
     * Count products within a category slug.
     */
    public long countActiveByCategorySlug(String categorySlug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_ACTIVE_BY_CATEGORY_SLUG,
                Parameters.with("tenantId", tenantId).and("categorySlug", categorySlug));
    }

    /**
     * Find products assigned to a collection slug.
     */
    public List<Product> findActiveByCollectionSlug(String collectionSlug, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_ACTIVE_BY_COLLECTION_SLUG,
                Parameters.with("tenantId", tenantId).and("collectionSlug", collectionSlug)).page(Page.of(page, size))
                .list();
    }

    /**
     * Count products within a collection slug.
     */
    public long countActiveByCollectionSlug(String collectionSlug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_ACTIVE_BY_COLLECTION_SLUG,
                Parameters.with("tenantId", tenantId).and("collectionSlug", collectionSlug));
    }

    /**
     * Find products that belong to both a category and collection slug.
     */
    public List<Product> findActiveByCategoryAndCollection(String categorySlug, String collectionSlug, int page,
            int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_ACTIVE_BY_CATEGORY_AND_COLLECTION, Parameters.with("tenantId", tenantId)
                .and("categorySlug", categorySlug).and("collectionSlug", collectionSlug)).page(Page.of(page, size))
                .list();
    }

    /**
     * Count products matching both category and collection filter.
     */
    public long countActiveByCategoryAndCollection(String categorySlug, String collectionSlug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_ACTIVE_BY_CATEGORY_AND_COLLECTION, Parameters.with("tenantId", tenantId)
                .and("categorySlug", categorySlug).and("collectionSlug", collectionSlug));
    }
}
