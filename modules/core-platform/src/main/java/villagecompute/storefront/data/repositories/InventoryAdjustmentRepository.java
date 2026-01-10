package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.AdjustmentReason;
import villagecompute.storefront.data.models.InventoryAdjustment;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for InventoryAdjustment entity with tenant-aware queries.
 *
 * <p>
 * Manages audit trail for manual inventory adjustments, always scoped to the current tenant.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow (adjustments)</li>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryAdjustmentRepository implements PanacheRepositoryBase<InventoryAdjustment, UUID> {

    private static final String QUERY_FIND_BY_VARIANT = "tenant.id = :tenantId and variant.id = :variantId";
    private static final String QUERY_FIND_BY_LOCATION = "tenant.id = :tenantId and location.id = :locationId";
    private static final String QUERY_FIND_BY_REASON = "tenant.id = :tenantId and reason = :reason";
    private static final String QUERY_FIND_BY_DATE_RANGE = "tenant.id = :tenantId and createdAt >= :startDate and createdAt <= :endDate";

    /**
     * Find all adjustments for a variant.
     *
     * @param variantId
     *            variant UUID
     * @return list of adjustments
     */
    public List<InventoryAdjustment> findByVariant(UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_VARIANT, Parameters.with("tenantId", tenantId).and("variantId", variantId));
    }

    /**
     * Find all adjustments for a location.
     *
     * @param locationId
     *            location UUID
     * @return list of adjustments
     */
    public List<InventoryAdjustment> findByLocation(UUID locationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_LOCATION, Parameters.with("tenantId", tenantId).and("locationId", locationId));
    }

    /**
     * Find all adjustments by reason code.
     *
     * @param reason
     *            adjustment reason
     * @return list of adjustments
     */
    public List<InventoryAdjustment> findByReason(AdjustmentReason reason) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_REASON, Parameters.with("tenantId", tenantId).and("reason", reason));
    }

    /**
     * Find adjustments within a date range.
     *
     * @param startDate
     *            range start
     * @param endDate
     *            range end
     * @return list of adjustments
     */
    public List<InventoryAdjustment> findByDateRange(OffsetDateTime startDate, OffsetDateTime endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_DATE_RANGE,
                Parameters.with("tenantId", tenantId).and("startDate", startDate).and("endDate", endDate));
    }
}
