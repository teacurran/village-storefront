package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for ProductVariant entity with tenant-aware queries.
 *
 * <p>
 * Manages product variants with automatic tenant scoping. All queries filter by the current tenant to prevent
 * cross-tenant data access.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link ProductVariant}</li>
 * </ul>
 */
@ApplicationScoped
public class ProductVariantRepository implements PanacheRepositoryBase<ProductVariant, UUID> {

    private static final String QUERY_FIND_BY_PRODUCT = "tenant.id = :tenantId and product.id = :productId and status = 'active' order by position";
    private static final String QUERY_FIND_BY_TENANT_AND_SKU = "tenant.id = :tenantId and sku = :sku";

    /**
     * Find all active variants for a product.
     *
     * @param productId
     *            product UUID
     * @return list of variants ordered by position
     */
    public List<ProductVariant> findByProduct(UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_PRODUCT, Parameters.with("tenantId", tenantId).and("productId", productId));
    }

    /**
     * Find variant by SKU within current tenant.
     *
     * @param sku
     *            variant SKU
     * @return variant if found
     */
    public Optional<ProductVariant> findBySku(String sku) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_SKU, Parameters.with("tenantId", tenantId).and("sku", sku))
                .firstResultOptional();
    }
}
