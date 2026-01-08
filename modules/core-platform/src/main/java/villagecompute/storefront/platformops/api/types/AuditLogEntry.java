package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit log entry DTO for platform command display.
 *
 * <p>
 * Simplified view of {@link villagecompute.storefront.platformops.data.models.PlatformCommand} for API responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (audit log viewer)</li>
 * </ul>
 */
public class AuditLogEntry {

    public UUID id;
    public String actorType;
    public UUID actorId;
    public String actorEmail;
    public String action;
    public String targetType;
    public UUID targetId;
    public String reason;
    public String ticketNumber;
    public OffsetDateTime occurredAt;
    public String ipAddress;

    public AuditLogEntry() {
    }

    public AuditLogEntry(UUID id, String actorType, UUID actorId, String actorEmail, String action, String targetType,
            UUID targetId, String reason, String ticketNumber, OffsetDateTime occurredAt, String ipAddress) {
        this.id = id;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorEmail = actorEmail;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.reason = reason;
        this.ticketNumber = ticketNumber;
        this.occurredAt = occurredAt;
        this.ipAddress = ipAddress;
    }
}
