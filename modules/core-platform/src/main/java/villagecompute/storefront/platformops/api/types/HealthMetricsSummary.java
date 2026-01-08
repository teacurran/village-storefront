package villagecompute.storefront.platformops.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * System health metrics summary DTO.
 *
 * <p>
 * Aggregated platform health metrics for dashboard widgets. Combines real-time Prometheus data with historical snapshot
 * data.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (health dashboards)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.7</li>
 * </ul>
 */
public class HealthMetricsSummary {

    public OffsetDateTime timestamp;
    public Integer tenantCount;
    public Integer activeTenantCount;
    public Integer totalUsers;
    public Integer activeSessions;
    public Integer jobQueueDepth;
    public Integer failedJobs24h;
    public BigDecimal avgResponseTimeMs;
    public BigDecimal p95ResponseTimeMs;
    public BigDecimal errorRatePercent;
    public BigDecimal diskUsagePercent;
    public Integer dbConnectionCount;
    public String status; // 'healthy', 'degraded', 'critical'

    public HealthMetricsSummary() {
    }

    public HealthMetricsSummary(OffsetDateTime timestamp, Integer tenantCount, Integer activeTenantCount,
            Integer totalUsers, Integer activeSessions, Integer jobQueueDepth, Integer failedJobs24h,
            BigDecimal avgResponseTimeMs, BigDecimal p95ResponseTimeMs, BigDecimal errorRatePercent,
            BigDecimal diskUsagePercent, Integer dbConnectionCount, String status) {
        this.timestamp = timestamp;
        this.tenantCount = tenantCount;
        this.activeTenantCount = activeTenantCount;
        this.totalUsers = totalUsers;
        this.activeSessions = activeSessions;
        this.jobQueueDepth = jobQueueDepth;
        this.failedJobs24h = failedJobs24h;
        this.avgResponseTimeMs = avgResponseTimeMs;
        this.p95ResponseTimeMs = p95ResponseTimeMs;
        this.errorRatePercent = errorRatePercent;
        this.diskUsagePercent = diskUsagePercent;
        this.dbConnectionCount = dbConnectionCount;
        this.status = status;
    }
}
