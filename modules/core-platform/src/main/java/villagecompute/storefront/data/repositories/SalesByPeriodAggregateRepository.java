package villagecompute.storefront.data.repositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.SalesByPeriodAggregate;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for SalesByPeriodAggregate entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. Provides read access to
 * pre-computed sales aggregates for dashboards and exports.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Entity: {@link SalesByPeriodAggregate}</li>
 * <li>Architecture: 02_System_Structure_and_Data.md</li>
 * </ul>
 */
@ApplicationScoped
public class SalesByPeriodAggregateRepository implements PanacheRepositoryBase<SalesByPeriodAggregate, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_PERIOD = "tenant.id = :tenantId and periodStart >= :start and periodEnd <= :end";
    private static final String QUERY_FIND_EXACT_PERIOD = "tenant.id = :tenantId and periodStart = :start and periodEnd = :end";

    /**
     * Find all aggregates for the current tenant ordered by period descending.
     *
     * @return list of sales aggregates
     */
    public List<SalesByPeriodAggregate> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_TENANT + " order by periodStart desc", Parameters.with("tenantId", tenantId));
    }

    /**
     * Find aggregates within a date range for the current tenant.
     *
     * @param start
     *            start date (inclusive)
     * @param end
     *            end date (inclusive)
     * @return list of sales aggregates within range
     */
    public List<SalesByPeriodAggregate> findByPeriodRange(LocalDate start, LocalDate end) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_PERIOD + " order by periodStart",
                Parameters.with("tenantId", tenantId).and("start", start).and("end", end));
    }

    /**
     * Find aggregate for exact period within current tenant.
     *
     * @param periodStart
     *            period start date
     * @param periodEnd
     *            period end date
     * @return aggregate if found
     */
    public Optional<SalesByPeriodAggregate> findByExactPeriod(LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_EXACT_PERIOD,
                Parameters.with("tenantId", tenantId).and("start", periodStart).and("end", periodEnd))
                .firstResultOptional();
    }
}
