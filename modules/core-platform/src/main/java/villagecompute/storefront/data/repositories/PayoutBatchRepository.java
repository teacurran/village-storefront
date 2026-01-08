package villagecompute.storefront.data.repositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for PayoutBatch entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link PayoutBatch}</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@ApplicationScoped
public class PayoutBatchRepository implements PanacheRepositoryBase<PayoutBatch, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_TENANT_AND_CONSIGNOR = "tenant.id = :tenantId and consignor.id = :consignorId";
    private static final String QUERY_FIND_BY_TENANT_AND_STATUS = "tenant.id = :tenantId and status = :status";
    private static final String QUERY_FIND_BY_CONSIGNOR_AND_STATUS = "tenant.id = :tenantId and consignor.id = :consignorId and status = :status";
    private static final String QUERY_FIND_BY_PERIOD = "tenant.id = :tenantId and consignor.id = :consignorId and periodStart = :periodStart and periodEnd = :periodEnd";

    /**
     * Find all payout batches for the current tenant.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of payout batches
     */
    public List<PayoutBatch> findByCurrentTenant(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId)).page(Page.of(page, size)).list();
    }

    /**
     * Find payout batches for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of batches
     */
    public List<PayoutBatch> findByConsignor(UUID consignorId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_CONSIGNOR,
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId)).page(Page.of(page, size)).list();
    }

    /**
     * Find payout batches by status.
     *
     * @param status
     *            batch status
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of batches
     */
    public List<PayoutBatch> findByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", status))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find payout batches for a consignor by status.
     *
     * @param consignorId
     *            consignor UUID
     * @param status
     *            batch status
     * @return list of batches
     */
    public List<PayoutBatch> findByConsignorAndStatus(UUID consignorId, String status) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_CONSIGNOR_AND_STATUS,
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId).and("status", status));
    }

    /**
     * Find existing batch for a consignor and period.
     *
     * @param consignorId
     *            consignor UUID
     * @param periodStart
     *            period start date
     * @param periodEnd
     *            period end date
     * @return batch if exists
     */
    public Optional<PayoutBatch> findByConsignorAndPeriod(UUID consignorId, LocalDate periodStart,
            LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_PERIOD, Parameters.with("tenantId", tenantId).and("consignorId", consignorId)
                .and("periodStart", periodStart).and("periodEnd", periodEnd)).firstResultOptional();
    }

    /**
     * Find payout batch by ID within current tenant with ownership verification.
     *
     * @param id
     *            batch UUID
     * @return batch if found and belongs to current tenant
     */
    public Optional<PayoutBatch> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Count pending payout batches for the current tenant.
     *
     * @return count of pending batches
     */
    public long countPendingByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count(QUERY_FIND_BY_TENANT_AND_STATUS, Parameters.with("tenantId", tenantId).and("status", "pending"));
    }
}
