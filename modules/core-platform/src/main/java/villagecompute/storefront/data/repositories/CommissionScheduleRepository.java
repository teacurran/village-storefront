package villagecompute.storefront.data.repositories;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.CommissionSchedule;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for CommissionSchedule entity with tenant-aware queries.
 *
 * <p>
 * Supports lookup of active commission schedules by consignor, category, and date range.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Commission schedule queries</li>
 * <li>Entity: {@link CommissionSchedule}</li>
 * </ul>
 */
@ApplicationScoped
public class CommissionScheduleRepository implements PanacheRepositoryBase<CommissionSchedule, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_ACTIVE_FOR_CONSIGNOR = "consignor.id = :consignorId and tenant.id = :tenantId and status = 'active' and effectiveFrom <= :asOfDate and (effectiveUntil is null or effectiveUntil >= :asOfDate)";
    private static final String QUERY_FIND_ACTIVE_FOR_CONSIGNOR_AND_CATEGORY = "consignor.id = :consignorId and categoryId = :categoryId and tenant.id = :tenantId and status = 'active' and effectiveFrom <= :asOfDate and (effectiveUntil is null or effectiveUntil >= :asOfDate)";

    /**
     * Find commission schedule by ID within current tenant.
     *
     * @param id
     *            schedule UUID
     * @return schedule if found
     */
    public Optional<CommissionSchedule> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }

    /**
     * Find active commission schedules for a consignor as of a specific date.
     *
     * @param consignorId
     *            consignor UUID
     * @param asOfDate
     *            date to check (defaults to today if null)
     * @return list of active schedules ordered by priority descending
     */
    public List<CommissionSchedule> findActiveForConsignor(UUID consignorId, LocalDate asOfDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LocalDate effectiveDate = asOfDate != null ? asOfDate : LocalDate.now();

        return find(QUERY_FIND_ACTIVE_FOR_CONSIGNOR,
                Parameters.with("consignorId", consignorId).and("tenantId", tenantId).and("asOfDate", effectiveDate))
                .stream().sorted((a, b) -> Integer.compare(b.priority, a.priority)).toList();
    }

    /**
     * Find active commission schedule for a consignor and category.
     *
     * @param consignorId
     *            consignor UUID
     * @param categoryId
     *            category UUID
     * @param asOfDate
     *            date to check
     * @return highest priority schedule if found
     */
    public Optional<CommissionSchedule> findActiveForConsignorAndCategory(UUID consignorId, UUID categoryId,
            LocalDate asOfDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LocalDate effectiveDate = asOfDate != null ? asOfDate : LocalDate.now();

        return find(QUERY_FIND_ACTIVE_FOR_CONSIGNOR_AND_CATEGORY,
                Parameters.with("consignorId", consignorId).and("categoryId", categoryId).and("tenantId", tenantId)
                        .and("asOfDate", effectiveDate))
                .stream().sorted((a, b) -> Integer.compare(b.priority, a.priority)).findFirst();
    }

    /**
     * Find all schedules for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @return list of schedules
     */
    public List<CommissionSchedule> findByConsignor(UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("consignor.id = :consignorId and tenant.id = :tenantId order by effectiveFrom desc",
                Parameters.with("consignorId", consignorId).and("tenantId", tenantId));
    }
}
