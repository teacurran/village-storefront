package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Current impersonation context DTO.
 *
 * <p>
 * Returned to clients to display impersonation banner and session details.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (impersonation banner)</li>
 * </ul>
 */
public class ImpersonationContext {

    public UUID sessionId;
    public UUID platformAdminId;
    public String platformAdminEmail;
    public UUID targetTenantId;
    public String targetTenantName;
    public UUID targetUserId; // Nullable
    public String targetUserEmail;
    public String reason;
    public String ticketNumber;
    public OffsetDateTime startedAt;

    public ImpersonationContext() {
    }

    public ImpersonationContext(UUID sessionId, UUID platformAdminId, String platformAdminEmail, UUID targetTenantId,
            String targetTenantName, UUID targetUserId, String targetUserEmail, String reason, String ticketNumber,
            OffsetDateTime startedAt) {
        this.sessionId = sessionId;
        this.platformAdminId = platformAdminId;
        this.platformAdminEmail = platformAdminEmail;
        this.targetTenantId = targetTenantId;
        this.targetTenantName = targetTenantName;
        this.targetUserId = targetUserId;
        this.targetUserEmail = targetUserEmail;
        this.reason = reason;
        this.ticketNumber = ticketNumber;
        this.startedAt = startedAt;
    }
}
