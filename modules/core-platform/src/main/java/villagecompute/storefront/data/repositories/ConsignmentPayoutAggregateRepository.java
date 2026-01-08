package villagecompute.storefront.data.repositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for ConsignmentPayoutAggregate entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Provides read access to
 * pre-computed consignment payout aggregates.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Entity: {@link ConsignmentPayoutAggregate}</li>
 * <li>Architecture: 02_System_Structure_and_Data.md</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentPayoutAggregateRepository implements PanacheRepositoryBase<ConsignmentPayoutAggregate, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_CONSIGNOR = "tenant.id = :tenantId and consignor.id = :consignorId";
    private static final String QUERY_FIND_BY_PERIOD = "tenant.id = :tenantId and periodStart >= :start and periodEnd <= :end";
    private static final String QUERY_FIND_EXACT = "tenant.id = :tenantId and consignor.id = :consignorId and periodStart = :start and periodEnd = :end";

    /**
     * Find all consignment payout aggregates for the current tenant.
     *
     * @return list of payout aggregates
     */
    public List<ConsignmentPayoutAggregate> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT + " order by periodStart desc", Parameters.with("tenantId", tenantId));
    }

    /**
     * Find all aggregates for a specific consignor within current tenant.
     *
     * @param consignorId
     *            consignor UUID
     * @return list of payout aggregates
     */
    public List<ConsignmentPayoutAggregate> findByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_CONSIGNOR + " order by periodStart desc",
                Parameters.with("tenantId", tenantId).and("consignorId", consignorId));
    }

    /**
     * Find aggregates within a date range for the current tenant.
     *
     * @param start
     *            start date (inclusive)
     * @param end
     *            end date (inclusive)
     * @return list of payout aggregates within range
     */
    public List<ConsignmentPayoutAggregate> findByPeriodRange(LocalDate start, LocalDate end) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_PERIOD + " order by periodStart",
                Parameters.with("tenantId", tenantId).and("start", start).and("end", end));
    }

    /**
     * Find aggregate for exact consignor and period within current tenant.
     *
     * @param consignorId
     *            consignor UUID
     * @param periodStart
     *            period start date
     * @param periodEnd
     *            period end date
     * @return aggregate if found
     */
    public Optional<ConsignmentPayoutAggregate> findExact(UUID consignorId, LocalDate periodStart,
            LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_EXACT, Parameters.with("tenantId", tenantId).and("consignorId", consignorId)
                .and("start", periodStart).and("end", periodEnd)).firstResultOptional();
    }
}
