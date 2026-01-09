package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.PayoutLedgerEntry;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for PayoutLedgerEntry entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This enforces RLS at the repository
 * level per ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link PayoutLedgerEntry}</li>
 * <li>Task I3.T5: Consignment payout ledger implementation</li>
 * </ul>
 */
@ApplicationScoped
public class PayoutLedgerEntryRepository implements PanacheRepositoryBase<PayoutLedgerEntry, UUID> {

    private static final String QUERY_FIND_BY_TENANT = "tenant.id = :tenantId";
    private static final String QUERY_FIND_BY_LEDGER = "tenant.id = :tenantId and ledger.id = :ledgerId";
    private static final String QUERY_FIND_BY_LEDGER_AND_TYPE = "tenant.id = :tenantId and ledger.id = :ledgerId and entryType = :entryType";
    private static final String QUERY_FIND_BY_LEDGER_AND_DATE_RANGE = "tenant.id = :tenantId and ledger.id = :ledgerId and createdAt >= :startDate and createdAt <= :endDate";

    /**
     * Find entries by ledger for the current tenant with pagination.
     *
     * @param ledgerId
     *            ledger UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of entries ordered by created_at descending
     */
    public List<PayoutLedgerEntry> findByLedger(UUID ledgerId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_LEDGER + " order by createdAt desc",
                Parameters.with("tenantId", tenantId).and("ledgerId", ledgerId)).page(Page.of(page, size)).list();
    }

    /**
     * Find entries by ledger and entry type.
     *
     * @param ledgerId
     *            ledger UUID
     * @param entryType
     *            entry type (SALE, REFUND, SETTLEMENT, PAYOUT, ADJUSTMENT)
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of entries
     */
    public List<PayoutLedgerEntry> findByLedgerAndType(UUID ledgerId, String entryType, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_LEDGER_AND_TYPE + " order by createdAt desc",
                Parameters.with("tenantId", tenantId).and("ledgerId", ledgerId).and("entryType", entryType))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find entries by ledger within date range.
     *
     * @param ledgerId
     *            ledger UUID
     * @param startDate
     *            start of date range (inclusive)
     * @param endDate
     *            end of date range (inclusive)
     * @return list of entries
     */
    public List<PayoutLedgerEntry> findByLedgerAndDateRange(UUID ledgerId, OffsetDateTime startDate,
            OffsetDateTime endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_LEDGER_AND_DATE_RANGE + " order by createdAt desc",
                Parameters.with("tenantId", tenantId).and("ledgerId", ledgerId).and("startDate", startDate)
                        .and("endDate", endDate));
    }

    /**
     * Find entry by ID within current tenant with ownership verification.
     *
     * @param id
     *            entry UUID
     * @return entry if found and belongs to current tenant
     */
    public Optional<PayoutLedgerEntry> findByIdAndTenant(UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("id = :id and tenant.id = :tenantId", Parameters.with("id", id).and("tenantId", tenantId))
                .firstResultOptional();
    }
}
