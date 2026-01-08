package villagecompute.storefront.platformops.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * System health snapshot entity. Periodic captures of platform-wide health metrics.
 *
 * <p>
 * Complements real-time Prometheus metrics with persisted data points for historical trending and dashboards. Captured
 * by scheduled jobs (typically every 5-15 minutes).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (health dashboards)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.7 (Observability)</li>
 * <li>Migration: V20260111__platform_admin_tables.sql</li>
 * </ul>
 */
@Entity
@Table(
        name = "system_health_snapshots")
public class SystemHealthSnapshot extends PanacheEntityBase {

    public static final String QUERY_FIND_RECENT = "SystemHealthSnapshot.findRecent";
    public static final String QUERY_FIND_BY_DATE_RANGE = "SystemHealthSnapshot.findByDateRange";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            name = "snapshot_at",
            nullable = false)
    public OffsetDateTime snapshotAt;

    @Column(
            name = "tenant_count",
            nullable = false)
    public Integer tenantCount;

    @Column(
            name = "active_tenant_count",
            nullable = false)
    public Integer activeTenantCount;

    @Column(
            name = "total_users",
            nullable = false)
    public Integer totalUsers;

    @Column(
            name = "active_sessions",
            nullable = false)
    public Integer activeSessions;

    @Column(
            name = "job_queue_depth",
            nullable = false)
    public Integer jobQueueDepth;

    @Column(
            name = "failed_jobs_24h",
            nullable = false)
    public Integer failedJobs24h;

    @Column(
            name = "avg_response_time_ms",
            precision = 10,
            scale = 2)
    public BigDecimal avgResponseTimeMs;

    @Column(
            name = "p95_response_time_ms",
            precision = 10,
            scale = 2)
    public BigDecimal p95ResponseTimeMs;

    @Column(
            name = "error_rate_percent",
            precision = 5,
            scale = 2)
    public BigDecimal errorRatePercent;

    @Column(
            name = "disk_usage_percent",
            precision = 5,
            scale = 2)
    public BigDecimal diskUsagePercent;

    @Column(
            name = "db_connection_count")
    public Integer dbConnectionCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String metrics; // Additional Prometheus metrics snapshot

    @PrePersist
    public void prePersist() {
        if (snapshotAt == null) {
            snapshotAt = OffsetDateTime.now();
        }
    }
}
