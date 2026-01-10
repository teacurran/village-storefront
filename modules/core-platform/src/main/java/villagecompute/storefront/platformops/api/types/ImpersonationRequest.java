package villagecompute.storefront.platformops.api.types;

import java.util.UUID;

/**
 * Request to start an impersonation session.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (impersonation control)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8.1</li>
 * </ul>
 */
public class ImpersonationRequest {

    public UUID targetTenantId;
    public UUID targetUserId; // Nullable: NULL = tenant admin mode
    public String reason; // Required, min 10 chars
    public String ticketNumber; // Optional support ticket reference

    public ImpersonationRequest() {
    }

    public ImpersonationRequest(UUID targetTenantId, UUID targetUserId, String reason, String ticketNumber) {
        this.targetTenantId = targetTenantId;
        this.targetUserId = targetUserId;
        this.reason = reason;
        this.ticketNumber = ticketNumber;
    }
}
