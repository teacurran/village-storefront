package villagecompute.storefront.platformops.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.platformops.api.types.HealthMetricsSummary;
import villagecompute.storefront.platformops.data.models.SystemHealthSnapshot;
import villagecompute.storefront.platformops.data.repositories.ImpersonationSessionRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.prometheus.PrometheusMeterRegistry;

/**
 * Health metrics service for platform monitoring dashboards.
 *
 * <p>
 * Aggregates system health data from multiple sources:
 * <ul>
 * <li>Database counts (tenants, users, sessions)</li>
 * <li>Prometheus metrics summaries (response times, error rates)</li>
 * <li>Historical snapshots for trending</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (health dashboards)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.7</li>
 * </ul>
 */
@ApplicationScoped
public class HealthMetricsService {

    private static final Logger LOG = Logger.getLogger(HealthMetricsService.class);

    @Inject
    ImpersonationSessionRepository impersonationSessionRepo;

    @Inject
    PrometheusMeterRegistry prometheusRegistry;

    /**
     * Get current system health summary.
     *
     * <p>
     * Combines real-time database queries with Prometheus metrics. In production, this would integrate with
     * micrometer/Prometheus client to fetch actual metrics.
     *
     * @return current health metrics summary
     */
    @Transactional
    public HealthMetricsSummary getCurrentHealth() {
        LOG.debug("Fetching current system health metrics");

        OffsetDateTime now = OffsetDateTime.now();

        // Count tenants
        long totalTenants = Tenant.count();
        long activeTenants = Tenant.count("status = 'active'");

        // Count users across all tenants
        long totalUsers = countTotalUsers();
        long activeSessions = countActiveSessions();

        int jobQueueDepth = (int) Math.round(readGauge("jobs.queue.depth"));
        int failedJobs24h = (int) Math.round(readGauge("jobs.queue.failures24h"));

        HttpMetrics httpMetrics = collectHttpMetrics();
        BigDecimal avgResponseTime = httpMetrics.avgResponseTimeMs();
        BigDecimal p95ResponseTime = httpMetrics.p95ResponseTimeMs();
        BigDecimal errorRate = httpMetrics.errorRatePercent();

        BigDecimal diskUsage = calculateDiskUsagePercent();
        int dbConnections = (int) Math.round(readGauge("vertx.jdbc.pool.inUse"));

        // Determine overall status
        String status = determineHealthStatus(errorRate, diskUsage, p95ResponseTime);

        HealthMetricsSummary summary = new HealthMetricsSummary(now, (int) totalTenants, (int) activeTenants,
                (int) totalUsers, (int) activeSessions, jobQueueDepth, failedJobs24h, avgResponseTime, p95ResponseTime,
                errorRate, diskUsage, dbConnections, status);

        LOG.infof("System health: %d tenants, %d users, %d sessions, status=%s", totalTenants, totalUsers,
                activeSessions, status);

        return summary;
    }

    /**
     * Capture current health snapshot for historical trending.
     *
     * <p>
     * Called periodically by scheduled job (e.g., every 5 minutes).
     */
    @Transactional
    public void captureHealthSnapshot() {
        LOG.debug("Capturing system health snapshot");

        HealthMetricsSummary current = getCurrentHealth();

        SystemHealthSnapshot snapshot = new SystemHealthSnapshot();
        snapshot.snapshotAt = OffsetDateTime.now();
        snapshot.tenantCount = current.tenantCount;
        snapshot.activeTenantCount = current.activeTenantCount;
        snapshot.totalUsers = current.totalUsers;
        snapshot.activeSessions = current.activeSessions;
        snapshot.jobQueueDepth = current.jobQueueDepth;
        snapshot.failedJobs24h = current.failedJobs24h;
        snapshot.avgResponseTimeMs = current.avgResponseTimeMs;
        snapshot.p95ResponseTimeMs = current.p95ResponseTimeMs;
        snapshot.errorRatePercent = current.errorRatePercent;
        snapshot.diskUsagePercent = current.diskUsagePercent;
        snapshot.dbConnectionCount = current.dbConnectionCount;
        snapshot.metrics = String.format("{\"avg_response_ms\":%s,\"p95_response_ms\":%s,\"error_rate\":%s}",
                current.avgResponseTimeMs, current.p95ResponseTimeMs, current.errorRatePercent);

        snapshot.persist();

        LOG.infof("Captured health snapshot: tenants=%d, users=%d, status=%s", snapshot.tenantCount,
                snapshot.totalUsers, current.status);
    }

    // --- Helper Methods ---

    private long countTotalUsers() {
        return User.count();
    }

    private long countActiveSessions() {
        long impersonationSessions = impersonationSessionRepo.countActiveSessions();
        long userSessions = Math.round(readGauge("auth.sessions.active"));
        return impersonationSessions + userSessions;
    }

    private String determineHealthStatus(BigDecimal errorRate, BigDecimal diskUsage, BigDecimal p95ResponseTime) {
        // Simple health scoring logic
        if (errorRate.compareTo(BigDecimal.valueOf(5.0)) > 0 || diskUsage.compareTo(BigDecimal.valueOf(90.0)) > 0
                || p95ResponseTime.compareTo(BigDecimal.valueOf(1000.0)) > 0) {
            return "critical";
        } else if (errorRate.compareTo(BigDecimal.valueOf(1.0)) > 0 || diskUsage.compareTo(BigDecimal.valueOf(75.0)) > 0
                || p95ResponseTime.compareTo(BigDecimal.valueOf(500.0)) > 0) {
            return "degraded";
        } else {
            return "healthy";
        }
    }

    private HttpMetrics collectHttpMetrics() {
        List<Meter> meters = prometheusRegistry != null ? prometheusRegistry.getMeters() : List.of();
        double totalCount = 0;
        double totalTime = 0;
        double errorCount = 0;
        double p95 = 0;

        for (Meter meter : meters) {
            if (!(meter instanceof Timer timer) || !"http.server.requests".equals(timer.getId().getName())) {
                continue;
            }
            long count = timer.count();
            totalCount += count;
            totalTime += timer.totalTime(TimeUnit.MILLISECONDS);
            if (isErrorTimer(timer)) {
                errorCount += count;
            }
            p95 = Math.max(p95, resolvePercentile(timer, 0.95));
        }

        BigDecimal avgResponse = totalCount > 0
                ? BigDecimal.valueOf(totalTime / totalCount).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal p95Response = BigDecimal.valueOf(p95).setScale(2, RoundingMode.HALF_UP);
        BigDecimal errorRate = totalCount > 0
                ? BigDecimal.valueOf((errorCount / totalCount) * 100).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return new HttpMetrics(avgResponse, p95Response, errorRate);
    }

    private boolean isErrorTimer(Timer timer) {
        for (Tag tag : timer.getId().getTags()) {
            if ("status".equals(tag.getKey()) && tag.getValue() != null && tag.getValue().startsWith("5")) {
                return true;
            }
            if ("outcome".equals(tag.getKey()) && "SERVER_ERROR".equalsIgnoreCase(tag.getValue())) {
                return true;
            }
        }
        return false;
    }

    private double resolvePercentile(Timer timer, double percentile) {
        HistogramSnapshot snapshot = timer.takeSnapshot();
        return Arrays.stream(snapshot.percentileValues()).filter(v -> Math.abs(v.percentile() - percentile) < 0.0001)
                .findFirst().map(v -> v.value(TimeUnit.MILLISECONDS)).orElse(snapshot.max(TimeUnit.MILLISECONDS));
    }

    private BigDecimal calculateDiskUsagePercent() {
        double used = readGauge("process_filesystem_usage_bytes");
        double total = readGauge("process_filesystem_total_bytes");
        if (used <= 0 || total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf((used / total) * 100).setScale(2, RoundingMode.HALF_UP);
    }

    private double readGauge(String metricName) {
        if (prometheusRegistry == null) {
            return 0;
        }
        return prometheusRegistry.getMeters().stream()
                .filter(m -> m instanceof Gauge gauge && metricName.equals(gauge.getId().getName()))
                .map(m -> ((Gauge) m).value()).findFirst().orElse(0d);
    }

    private record HttpMetrics(BigDecimal avgResponseTimeMs, BigDecimal p95ResponseTimeMs,
            BigDecimal errorRatePercent) {
    }
}
