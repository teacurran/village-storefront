package villagecompute.storefront.services.jobs;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Payload for background jobs that import catalog data from CSV files.
 *
 * <p>
 * Captures tenant context, import file location, processing options, and idempotency token. Immutable and serializable
 * for job persistence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
public final class CatalogImportJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final String fileLocation; // Temp path or object storage key
    private final String requestedBy;
    private final Map<String, String> options; // e.g., skipValidation, upsertMode
    private final String idempotencyToken; // For duplicate detection
    private final OffsetDateTime createdAt;
    private final int version; // Payload schema version

    private CatalogImportJobPayload(UUID jobId, UUID tenantId, String fileLocation, String requestedBy,
            Map<String, String> options, String idempotencyToken, OffsetDateTime createdAt, int version) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.fileLocation = fileLocation;
        this.requestedBy = requestedBy;
        this.options = options;
        this.idempotencyToken = idempotencyToken;
        this.createdAt = createdAt;
        this.version = version;
    }

    public static CatalogImportJobPayload create(UUID tenantId, String fileLocation, String requestedBy,
            Map<String, String> options) {
        return new CatalogImportJobPayload(UUID.randomUUID(), tenantId, fileLocation, requestedBy, options,
                UUID.randomUUID().toString(), OffsetDateTime.now(), 1);
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getFileLocation() {
        return fileLocation;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Map<String, String> getOptions() {
        return options;
    }

    public String getIdempotencyToken() {
        return idempotencyToken;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public int getVersion() {
        return version;
    }
}
