package villagecompute.storefront.data.repositories;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.LoyaltyAggregate;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepository;

/**
 * Repository for loyalty aggregates table.
 *
 * <p>
 * Provides query methods for loyalty program reporting metrics.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Architecture: docs/architecture/04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyAggregateRepository implements PanacheRepository<LoyaltyAggregate> {

    /**
     * Find all aggregates for current tenant.
     *
     * @return list of loyalty aggregates
     */
    public List<LoyaltyAggregate> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("tenant.id = ?1 ORDER BY periodDate DESC", tenantId).list();
    }

    /**
     * Find aggregates for current tenant within date range.
     *
     * @param startDate
     *            start of period
     * @param endDate
     *            end of period
     * @return list of loyalty aggregates
     */
    public List<LoyaltyAggregate> findByCurrentTenantAndPeriod(LocalDate startDate, LocalDate endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("tenant.id = ?1 AND periodDate >= ?2 AND periodDate <= ?3 ORDER BY periodDate DESC", tenantId,
                startDate, endDate).list();
    }

    /**
     * Find aggregate for specific period and tenant.
     *
     * @param tenantId
     *            tenant ID
     * @param periodDate
     *            period date
     * @return loyalty aggregate or null
     */
    public LoyaltyAggregate findByTenantAndPeriod(UUID tenantId, LocalDate periodDate) {
        return find("tenant.id = ?1 AND periodDate = ?2", tenantId, periodDate).firstResult();
    }
}
