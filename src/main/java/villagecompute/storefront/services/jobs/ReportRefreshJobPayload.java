package villagecompute.storefront.services.jobs;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload for background jobs that refresh reporting aggregates.
 *
 * <p>
 * Captures tenant context, aggregate type, and refresh parameters. The payload is immutable and serializable for
 * persistence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
public final class ReportRefreshJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final String aggregateType; // sales_by_period, consignment_payout, inventory_aging
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final OffsetDateTime createdAt;

    private ReportRefreshJobPayload(UUID jobId, UUID tenantId, String aggregateType, LocalDate periodStart,
            LocalDate periodEnd, OffsetDateTime createdAt) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.aggregateType = aggregateType;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.createdAt = createdAt;
    }

    public static ReportRefreshJobPayload create(UUID tenantId, String aggregateType, LocalDate periodStart,
            LocalDate periodEnd) {
        return new ReportRefreshJobPayload(UUID.randomUUID(), tenantId, aggregateType, periodStart, periodEnd,
                OffsetDateTime.now());
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
