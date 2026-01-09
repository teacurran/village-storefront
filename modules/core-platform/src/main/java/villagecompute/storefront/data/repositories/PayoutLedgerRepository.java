package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.PayoutLedger;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for PayoutLedger entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This enforces RLS at the repository
 * level per ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link PayoutLedger}</li>
 * <li>Task I3.T5: Consignment payout ledger implementation</li>
 * </ul>
 */
@ApplicationScoped
public class PayoutLedgerRepository implements PanacheRepositoryBase<PayoutLedger, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_CONSIGNOR = "tenant.id = :tenantId and consignor.id = :consignorId";

    /**
     * Find ledger by consignor for the current tenant.
     *
     * @param consignorId
     *            consignor UUID
     * @return ledger if found and belongs to current tenant
     */
    public Optional<PayoutLedger> findByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_CONSIGNOR, Parameters.with("tenantId", tenantId).and("consignorId", consignorId))
                .firstResultOptional();
    }

    /**
     * Find ledger by ID within current tenant with ownership verification.
     *
     * @param id
     *            ledger UUID
     * @return ledger if found and belongs to current tenant
     */
    public Optional<PayoutLedger> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Find all ledgers for the current tenant.
     *
     * @return list of ledgers
     */
    public List<PayoutLedger> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT, Parameters.with("tenantId", tenantId));
    }
}
