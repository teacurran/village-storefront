package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.PayoutLineItem;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for PayoutLineItem entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link PayoutLineItem}</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@ApplicationScoped
public class PayoutLineItemRepository implements PanacheRepositoryBase<PayoutLineItem, UUID> {

    private static final String QUERY_FIND_BY_BATCH = "tenant.id = :tenantId and batch.id = :batchId";

    /**
     * Find all line items for a payout batch.
     *
     * @param batchId
     *            payout batch UUID
     * @return list of line items
     */
    public List<PayoutLineItem> findByBatch(UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_BATCH, Parameters.with("tenantId", tenantId).and("batchId", batchId));
    }

    /**
     * Count line items in a batch.
     *
     * @param batchId
     *            payout batch UUID
     * @return count of line items
     */
    public long countByBatch(UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_BATCH, Parameters.with("tenantId", tenantId).and("batchId", batchId));
    }
}
