package villagecompute.storefront.compliance.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload for background jobs that generate privacy data exports.
 *
 * <p>
 * Captures tenant context, privacy request ID, and subject identifier hash (no plaintext PII). Immutable for safe job
 * queue persistence.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I5.T6 - Compliance automation (privacy export workflow)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 5 (background jobs must hash identifiers)</li>
 * </ul>
 */
public final class PrivacyExportJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final UUID privacyRequestId;
    private final String subjectIdentifierHash; // SHA-256 hash, no plaintext PII
    private final String requestedBy;
    private final OffsetDateTime createdAt;

    private PrivacyExportJobPayload(UUID jobId, UUID tenantId, UUID privacyRequestId, String subjectIdentifierHash,
            String requestedBy, OffsetDateTime createdAt) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.privacyRequestId = privacyRequestId;
        this.subjectIdentifierHash = subjectIdentifierHash;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
    }

    public static PrivacyExportJobPayload create(UUID tenantId, UUID privacyRequestId, String subjectIdentifierHash,
            String requestedBy) {
        return new PrivacyExportJobPayload(UUID.randomUUID(), tenantId, privacyRequestId, subjectIdentifierHash,
                requestedBy, OffsetDateTime.now());
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getPrivacyRequestId() {
        return privacyRequestId;
    }

    public String getSubjectIdentifierHash() {
        return subjectIdentifierHash;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
