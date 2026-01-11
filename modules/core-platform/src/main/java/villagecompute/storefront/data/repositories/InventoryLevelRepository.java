package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for InventoryLevel entity with tenant-aware queries.
 *
 * <p>
 * Manages inventory tracking across multiple locations for product variants, always scoped to the current tenant.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link InventoryLevel}</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryLevelRepository implements PanacheRepositoryBase<InventoryLevel, UUID> {

    private static final String QUERY_FIND_BY_VARIANT = "tenant.id = :tenantId and variant.id = :variantId";
    private static final String QUERY_FIND_BY_VARIANT_AND_LOCATION = "tenant.id = :tenantId and variant.id = :variantId and location = :location";
    private static final String QUERY_FIND_BY_LOCATION = "tenant.id = :tenantId and location = :location";
    private static final String QUERY_FIND_LOW_STOCK = "tenant.id = :tenantId and lowStockThreshold > 0 and (quantity - reserved) <= lowStockThreshold";

    /**
     * Find all inventory levels for a variant across all locations.
     *
     * @param variantId
     *            variant UUID
     * @return list of inventory levels
     */
    public List<InventoryLevel> findByVariant(UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_VARIANT, Parameters.with("tenantId", tenantId).and("variantId", variantId));
    }

    /**
     * Find inventory level for a specific variant and location.
     *
     * @param variantId
     *            variant UUID
     * @param location
     *            location identifier
     * @return inventory level if found
     */
    public Optional<InventoryLevel> findByVariantAndLocation(UUID variantId, String location) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_VARIANT_AND_LOCATION,
                Parameters.with("tenantId", tenantId).and("variantId", variantId).and("location", location))
                .firstResultOptional();
    }

    /**
     * Find all inventory levels at a specific location.
     *
     * @param location
     *            location identifier
     * @return list of inventory levels
     */
    public List<InventoryLevel> findByLocation(String location) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_LOCATION, Parameters.with("tenantId", tenantId).and("location", location));
    }

    /**
     * Calculate total available quantity for a variant across all locations.
     *
     * @param variantId
     *            variant UUID
     * @return total available quantity
     */
    public int getTotalAvailableQuantity(UUID variantId) {
        return findByVariant(variantId).stream().mapToInt(InventoryLevel::getAvailableQuantity).sum();
    }

    /**
     * Find all inventory levels that are below their configured low stock threshold.
     *
     * <p>
     * Only returns levels where lowStockThreshold is configured (> 0) and current quantity is at or below that
     * threshold. Used by the low stock alert scheduler to identify items needing attention.
     *
     * @return list of low stock inventory levels
     */
    public List<InventoryLevel> findLowStockItems() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_LOW_STOCK, Parameters.with("tenantId", tenantId));
    }

    /**
     * Count total inventory levels for current tenant.
     *
     * @return count of inventory levels
     */
    public int countForTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return (int) count("tenant.id", tenantId);
    }

    /**
     * Clear the underlying persistence context. Used by retry logic when optimistic locking conflicts occur.
     */
    public void clear() {
        getEntityManager().clear();
    }
}
