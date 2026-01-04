package villagecompute.storefront.compliance.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Payload for background jobs that execute privacy deletion workflows.
 *
 * <p>
 * Supports two-phase deletion: soft-delete (mark records) then purge (permanent deletion after retention). Stores only
 * hashed identifiers per foundation requirements.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I5.T6 - Compliance automation (privacy delete workflow)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 5 (background jobs must hash identifiers)</li>
 * </ul>
 */
public final class PrivacyDeleteJobPayload {

    private final UUID jobId;
    private final UUID tenantId;
    private final UUID privacyRequestId;
    private final String subjectIdentifierHash; // SHA-256 hash, no plaintext PII
    private final boolean isPurge; // true = permanent purge, false = soft-delete
    private final String requestedBy;
    private final OffsetDateTime createdAt;

    private PrivacyDeleteJobPayload(UUID jobId, UUID tenantId, UUID privacyRequestId, String subjectIdentifierHash,
            boolean isPurge, String requestedBy, OffsetDateTime createdAt) {
        this.jobId = jobId;
        this.tenantId = tenantId;
        this.privacyRequestId = privacyRequestId;
        this.subjectIdentifierHash = subjectIdentifierHash;
        this.isPurge = isPurge;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
    }

    public static PrivacyDeleteJobPayload createSoftDelete(UUID tenantId, UUID privacyRequestId,
            String subjectIdentifierHash, String requestedBy) {
        return new PrivacyDeleteJobPayload(UUID.randomUUID(), tenantId, privacyRequestId, subjectIdentifierHash, false,
                requestedBy, OffsetDateTime.now());
    }

    public static PrivacyDeleteJobPayload createPurge(UUID tenantId, UUID privacyRequestId,
            String subjectIdentifierHash, String requestedBy) {
        return new PrivacyDeleteJobPayload(UUID.randomUUID(), tenantId, privacyRequestId, subjectIdentifierHash, true,
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

    public boolean isPurge() {
        return isPurge;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
