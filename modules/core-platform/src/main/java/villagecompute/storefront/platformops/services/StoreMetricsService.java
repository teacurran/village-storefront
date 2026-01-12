package villagecompute.storefront.platformops.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.platformops.api.types.PlatformMetricsResponse;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Store metrics service for platform-level KPI aggregation.
 *
 * <p>
 * Computes metrics across all tenants using read-optimized projection tables to avoid impacting transactional
 * workloads. All metrics include data freshness timestamps per Section 5 governance requirements.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T1: Platform admin backend (metrics APIs)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 4 (Platform Admin Component KPIs)</li>
 * <li>Architecture: 02_System_Structure_and_Data.md (Reporting Projection Service)</li>
 * </ul>
 */
@ApplicationScoped
public class StoreMetricsService {

    private static final Logger LOG = Logger.getLogger(StoreMetricsService.class);

    @Inject
    EntityManager entityManager;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Get platform-wide metrics summary.
     *
     * <p>
     * Aggregates KPIs from read models including store counts, user counts, order volume, revenue, and product catalog
     * size. Metrics are computed cross-tenant using native queries for performance.
     *
     * @param startDate
     *            optional start date for time-bounded metrics (orders, revenue)
     * @param endDate
     *            optional end date for time-bounded metrics
     * @return platform metrics response with freshness timestamp
     */
    @Transactional
    public PlatformMetricsResponse getPlatformMetrics(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        OffsetDateTime computedAt = OffsetDateTime.now();

        try {
            LOG.infof("Computing platform metrics - startDate=%s, endDate=%s", startDate, endDate);

            // Store counts
            Long totalStores = entityManager.createQuery("SELECT COUNT(t) FROM Tenant t", Long.class).getSingleResult();

            Long activeStores = entityManager
                    .createQuery("SELECT COUNT(t) FROM Tenant t WHERE t.status = 'active'", Long.class)
                    .getSingleResult();

            Long suspendedStores = entityManager
                    .createQuery("SELECT COUNT(t) FROM Tenant t WHERE t.status = 'suspended'", Long.class)
                    .getSingleResult();

            // User counts (cross-tenant)
            Long totalUsers = entityManager.createQuery("SELECT COUNT(u) FROM User u", Long.class).getSingleResult();

            Long activeUsers = entityManager
                    .createQuery("SELECT COUNT(u) FROM User u WHERE u.status = 'active'", Long.class).getSingleResult();

            SalesAggregateMetrics aggregateMetrics = computeSalesAggregates(startDate, endDate);
            long totalOrders = aggregateMetrics.orderCount;
            BigDecimal totalRevenue = aggregateMetrics.totalAmount != null ? aggregateMetrics.totalAmount
                    : BigDecimal.ZERO;

            // Product count
            Long totalProducts = entityManager.createQuery("SELECT COUNT(p) FROM Product p", Long.class)
                    .getSingleResult();

            PlatformMetricsResponse response = new PlatformMetricsResponse(
                    totalStores != null ? totalStores.intValue() : 0,
                    activeStores != null ? activeStores.intValue() : 0,
                    suspendedStores != null ? suspendedStores.intValue() : 0,
                    totalUsers != null ? totalUsers.intValue() : 0, activeUsers != null ? activeUsers.intValue() : 0,
                    totalOrders, totalRevenue, totalProducts != null ? totalProducts : 0L,
                    aggregateMetrics.freshnessTimestamp != null ? aggregateMetrics.freshnessTimestamp : computedAt);

            LOG.infof("Platform metrics computed: %d stores, %d orders, revenue=%s", response.totalStores,
                    response.totalOrders, response.totalRevenue);

            return response;

        } finally {
            sample.stop(Timer.builder("platform.metrics.computation").description("Platform metrics aggregation time")
                    .register(meterRegistry));
        }
    }

    private SalesAggregateMetrics computeSalesAggregates(LocalDate startDate, LocalDate endDate) {
        StringBuilder jpql = new StringBuilder(
                "SELECT COALESCE(SUM(a.orderCount), 0), COALESCE(SUM(a.totalAmount), 0), MAX(a.dataFreshnessTimestamp) "
                        + "FROM SalesByPeriodAggregate a");
        List<String> predicates = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();

        if (startDate != null) {
            predicates.add("a.periodEnd >= :startDate");
            params.put("startDate", startDate);
        }
        if (endDate != null) {
            predicates.add("a.periodStart <= :endDate");
            params.put("endDate", endDate);
        }

        if (!predicates.isEmpty()) {
            jpql.append(" WHERE ").append(String.join(" AND ", predicates));
        }

        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        params.forEach(query::setParameter);

        Object[] result = query.getSingleResult();
        long orderCount = result[0] != null ? ((Number) result[0]).longValue() : 0L;
        BigDecimal totalAmount = result[1] != null ? (BigDecimal) result[1] : BigDecimal.ZERO;
        OffsetDateTime freshness = result[2] != null ? (OffsetDateTime) result[2] : null;

        return new SalesAggregateMetrics(orderCount, totalAmount, freshness);
    }

    private static final class SalesAggregateMetrics {
        private final long orderCount;
        private final BigDecimal totalAmount;
        private final OffsetDateTime freshnessTimestamp;

        private SalesAggregateMetrics(long orderCount, BigDecimal totalAmount, OffsetDateTime freshnessTimestamp) {
            this.orderCount = orderCount;
            this.totalAmount = totalAmount;
            this.freshnessTimestamp = freshnessTimestamp;
        }
    }
}
