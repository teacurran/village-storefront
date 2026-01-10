package villagecompute.storefront.services.jobs;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for background jobs that export catalog data to CSV files.
 *
 * <p>
 * Captures tenant context, export filters, output format options, and destination. Immutable and serializable for job
 * persistence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
public final class CatalogExportJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final String requestedBy;
    private final Map<String, String> filters; // e.g., status, category, collection
    private final String format; // csv (future: xlsx, json)
    private final OffsetDateTime createdAt;
    private final int version; // Payload schema version

    private CatalogExportJobPayload(UUID jobId, UUID tenantId, String requestedBy, Map<String, String> filters,
            String format, OffsetDateTime createdAt, int version) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.requestedBy = requestedBy;
        this.filters = filters;
        this.format = format;
        this.createdAt = createdAt;
        this.version = version;
    }

    public static CatalogExportJobPayload create(UUID tenantId, String requestedBy, Map<String, String> filters,
            String format) {
        return new CatalogExportJobPayload(UUID.randomUUID(), tenantId, requestedBy, filters, format,
                OffsetDateTime.now(), 1);
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Map<String, String> getFilters() {
        return filters;
    }

    public String getFormat() {
        return format;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public int getVersion() {
        return version;
    }
}
