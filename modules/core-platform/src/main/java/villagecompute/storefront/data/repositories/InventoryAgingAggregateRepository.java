package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.InventoryAgingAggregate;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for InventoryAgingAggregate entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Provides read access to
 * pre-computed inventory aging metrics.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Entity: {@link InventoryAgingAggregate}</li>
 * <li>Architecture: 02_System_Structure_and_Data.md</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryAgingAggregateRepository implements PanacheRepositoryBase<InventoryAgingAggregate, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_LOCATION = "tenant.id = :tenantId and location.id = :locationId";
    private static final String QUERY_FIND_BY_VARIANT = "tenant.id = :tenantId and variant.id = :variantId";
    private static final String QUERY_FIND_SLOW_MOVERS = "tenant.id = :tenantId and daysInStock >= :minDays";
    private static final String QUERY_FIND_EXACT = "tenant.id = :tenantId and variant.id = :variantId and location.id = :locationId";

    /**
     * Find all inventory aging aggregates for the current tenant ordered by days in stock descending.
     *
     * @return list of aging aggregates
     */
    public List<InventoryAgingAggregate> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT + " order by daysInStock desc", Parameters.with("tenantId", tenantId));
    }

    /**
     * Find aging aggregates for a specific location within current tenant.
     *
     * @param locationId
     *            location UUID
     * @return list of aging aggregates
     */
    public List<InventoryAgingAggregate> findByLocation(UUID locationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_LOCATION + " order by daysInStock desc",
                Parameters.with("tenantId", tenantId).and("locationId", locationId));
    }

    /**
     * Find aging aggregates for a specific variant across all locations.
     *
     * @param variantId
     *            variant UUID
     * @return list of aging aggregates
     */
    public List<InventoryAgingAggregate> findByVariant(UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_VARIANT + " order by daysInStock desc",
                Parameters.with("tenantId", tenantId).and("variantId", variantId));
    }

    /**
     * Find slow-moving inventory items (exceeding minimum days threshold).
     *
     * @param minDays
     *            minimum days in stock to qualify as slow-moving
     * @return list of aging aggregates for slow movers
     */
    public List<InventoryAgingAggregate> findSlowMovers(int minDays) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_SLOW_MOVERS + " order by daysInStock desc",
                Parameters.with("tenantId", tenantId).and("minDays", minDays));
    }

    /**
     * Find aggregate for exact variant and location within current tenant.
     *
     * @param variantId
     *            variant UUID
     * @param locationId
     *            location UUID
     * @return aggregate if found
     */
    public Optional<InventoryAgingAggregate> findExact(UUID variantId, UUID locationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_EXACT,
                Parameters.with("tenantId", tenantId).and("variantId", variantId).and("locationId", locationId))
                .firstResultOptional();
    }
}
