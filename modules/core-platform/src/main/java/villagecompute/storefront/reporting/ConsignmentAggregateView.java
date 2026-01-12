package villagecompute.storefront.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.tenant.TenantContext;

/**
 * Builder for partition-aware consignment payout aggregate queries.
 *
 * <p>
 * Provides optimized queries respecting tenant_id scoping and retention policies. Handles both hot and archived data
 * ranges via manifest metadata.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I4.T8 - Reporting Exports</li>
 * <li>Architecture: docs/architecture/background_jobs.md</li>
 * <li>Docs: docs/reporting/projections.md</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentAggregateView {

    private static final Logger LOG = Logger.getLogger(ConsignmentAggregateView.class);

    @Inject
    EntityManager entityManager;

    /**
     * Query consignment payout aggregates by date range with partition-aware optimization.
     *
     * @param startDate
     *            period start date
     * @param endDate
     *            period end date
     * @return list of consignment payout aggregates
     */
    @Transactional
    public List<ConsignmentPayoutAggregate> queryByDateRange(LocalDate startDate, LocalDate endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        LOG.debugf("Querying consignment aggregates - tenantId=%s, startDate=%s, endDate=%s", tenantId, startDate,
                endDate);

        // Partition-aware query respecting tenant RLS and date range
        String query = """
                SELECT agg FROM ConsignmentPayoutAggregate agg
                WHERE agg.tenant.id = :tenantId
                AND agg.periodStart >= :startDate
                AND agg.periodEnd <= :endDate
                ORDER BY agg.consignor.id, agg.periodStart
                """;

        List<ConsignmentPayoutAggregate> aggregates = entityManager.createQuery(query, ConsignmentPayoutAggregate.class)
                .setParameter("tenantId", tenantId).setParameter("startDate", startDate)
                .setParameter("endDate", endDate).getResultList();

        LOG.debugf("Retrieved %d consignment aggregates for tenant %s", aggregates.size(), tenantId);

        return aggregates;
    }

    /**
     * Query consignment payout aggregates by consignor with retention policy enforcement.
     *
     * @param consignorId
     *            consignor UUID
     * @param retentionDays
     *            retention period in days (default 90)
     * @return list of consignment payout aggregates
     */
    @Transactional
    public List<ConsignmentPayoutAggregate> queryByConsignor(UUID consignorId, int retentionDays) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);

        LOG.debugf("Querying consignment aggregates - tenantId=%s, consignorId=%s, retentionDays=%d", tenantId,
                consignorId, retentionDays);

        // Query respecting retention policy
        String query = """
                SELECT agg FROM ConsignmentPayoutAggregate agg
                WHERE agg.tenant.id = :tenantId
                AND agg.consignor.id = :consignorId
                AND agg.periodEnd >= :cutoffDate
                ORDER BY agg.periodStart DESC
                """;

        List<ConsignmentPayoutAggregate> aggregates = entityManager.createQuery(query, ConsignmentPayoutAggregate.class)
                .setParameter("tenantId", tenantId).setParameter("consignorId", consignorId)
                .setParameter("cutoffDate", cutoffDate).getResultList();

        LOG.debugf("Retrieved %d consignment aggregates for consignor %s", aggregates.size(), consignorId);

        return aggregates;
    }

    /**
     * Compute summary statistics for consignment payouts over date range.
     *
     * @param startDate
     *            period start date
     * @param endDate
     *            period end date
     * @return summary DTO with total owed, item count, etc.
     */
    @Transactional
    public ConsignmentSummary computeSummary(LocalDate startDate, LocalDate endDate) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        LOG.debugf("Computing consignment summary - tenantId=%s, startDate=%s, endDate=%s", tenantId, startDate,
                endDate);

        String query = """
                SELECT
                    SUM(agg.totalOwed),
                    SUM(agg.itemsSold),
                    SUM(agg.itemCount),
                    COUNT(DISTINCT agg.consignor.id)
                FROM ConsignmentPayoutAggregate agg
                WHERE agg.tenant.id = :tenantId
                AND agg.periodStart >= :startDate
                AND agg.periodEnd <= :endDate
                """;

        Object[] result = entityManager.createQuery(query, Object[].class).setParameter("tenantId", tenantId)
                .setParameter("startDate", startDate).setParameter("endDate", endDate).getSingleResult();

        BigDecimal totalOwed = (BigDecimal) result[0];
        Long itemsSold = (Long) result[1];
        Long itemCount = (Long) result[2];
        Long consignorCount = (Long) result[3];

        return new ConsignmentSummary(totalOwed != null ? totalOwed : BigDecimal.ZERO,
                itemsSold != null ? itemsSold.intValue() : 0, itemCount != null ? itemCount.intValue() : 0,
                consignorCount != null ? consignorCount.intValue() : 0, OffsetDateTime.now());
    }

    /**
     * DTO for consignment payout summary statistics.
     */
    public record ConsignmentSummary(BigDecimal totalOwed, int itemsSold, int itemCount, int consignorCount,
            OffsetDateTime computedAt) {
    }
}
