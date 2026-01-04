package villagecompute.storefront.services.jobs;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for background jobs that export reports to cloud storage.
 *
 * <p>
 * Captures tenant context, report type, export format, and custom parameters. Immutable and serializable for job
 * persistence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
public final class ReportExportJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final UUID reportJobId; // Links to ReportJob entity
    private final String reportType; // sales_by_period, consignment_payout, inventory_aging
    private final String format; // csv, pdf, xlsx
    private final Map<String, String> parameters;
    private final String requestedBy;
    private final OffsetDateTime createdAt;

    private ReportExportJobPayload(UUID jobId, UUID tenantId, UUID reportJobId, String reportType, String format,
            Map<String, String> parameters, String requestedBy, OffsetDateTime createdAt) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.reportJobId = reportJobId;
        this.reportType = reportType;
        this.format = format;
        this.parameters = parameters;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
    }

    public static ReportExportJobPayload create(UUID tenantId, UUID reportJobId, String reportType, String format,
            Map<String, String> parameters, String requestedBy) {
        return new ReportExportJobPayload(UUID.randomUUID(), tenantId, reportJobId, reportType, format, parameters,
                requestedBy, OffsetDateTime.now());
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getReportJobId() {
        return reportJobId;
    }

    public String getReportType() {
        return reportType;
    }

    public String getFormat() {
        return format;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
