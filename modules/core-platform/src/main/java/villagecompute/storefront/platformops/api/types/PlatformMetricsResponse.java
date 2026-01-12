package villagecompute.storefront.platformops.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Platform-wide metrics summary DTO.
 *
 * <p>
 * Aggregates key performance indicators across all tenants for platform admin dashboard. All metrics are computed from
 * read-optimized projection tables to avoid impacting transactional workloads.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T1: Platform admin backend (metrics APIs)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 4 (Component KPIs)</li>
 * </ul>
 */
public class PlatformMetricsResponse {

    public int totalStores;
    public int activeStores;
    public int suspendedStores;
    public int totalUsers;
    public int activeUsers;
    public long totalOrders;
    public BigDecimal totalRevenue;
    public long totalProducts;
    public OffsetDateTime dataFreshnessTimestamp;

    public PlatformMetricsResponse() {
    }

    public PlatformMetricsResponse(int totalStores, int activeStores, int suspendedStores, int totalUsers,
            int activeUsers, long totalOrders, BigDecimal totalRevenue, long totalProducts,
            OffsetDateTime dataFreshnessTimestamp) {
        this.totalStores = totalStores;
        this.activeStores = activeStores;
        this.suspendedStores = suspendedStores;
        this.totalUsers = totalUsers;
        this.activeUsers = activeUsers;
        this.totalOrders = totalOrders;
        this.totalRevenue = totalRevenue;
        this.totalProducts = totalProducts;
        this.dataFreshnessTimestamp = dataFreshnessTimestamp;
    }
}
