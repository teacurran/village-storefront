package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for CartItem entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link CartItem}</li>
 * <li>Task I2.T4: Cart item repository with tenant isolation</li>
 * </ul>
 */
@ApplicationScoped
public class CartItemRepository implements PanacheRepositoryBase<CartItem, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_CART = "tenant.id = :tenantId and cart.id = :cartId";
    private static final String QUERY_FIND_BY_CART_AND_VARIANT = "tenant.id = :tenantId and cart.id = :cartId and variant.id = :variantId";
    private static final String QUERY_DELETE_BY_CART = "tenant.id = :tenantId and cart.id = :cartId";

    /**
     * Find all cart items for a specific cart within current tenant.
     *
     * @param cartId
     *            cart UUID
     * @return list of cart items
     */
    public List<CartItem> findByCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_CART, Parameters.with("tenantId", tenantId).and("cartId", cartId));
    }

    /**
     * Find a specific cart item by cart and variant within current tenant.
     *
     * @param cartId
     *            cart UUID
     * @param variantId
     *            variant UUID
     * @return cart item if found
     */
    public Optional<CartItem> findByCartAndVariant(UUID cartId, UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_CART_AND_VARIANT,
                Parameters.with("tenantId", tenantId).and("cartId", cartId).and("variantId", variantId))
                .firstResultOptional();
    }

    /**
     * Count cart items for a specific cart within current tenant.
     *
     * @param cartId
     *            cart UUID
     * @return cart item count
     */
    public long countByCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_CART, Parameters.with("tenantId", tenantId).and("cartId", cartId));
    }

    /**
     * Delete all cart items for a specific cart within current tenant.
     *
     * @param cartId
     *            cart UUID
     * @return number of deleted items
     */
    public long deleteByCart(UUID cartId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return delete(QUERY_DELETE_BY_CART, Parameters.with("tenantId", tenantId).and("cartId", cartId));
    }

    /**
     * Count all cart items for the current tenant.
     *
     * @return total cart item count
     */
    public long countByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }
}
