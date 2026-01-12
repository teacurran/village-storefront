package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tenant plan information DTO.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T1: Platform admin backend (plan management)</li>
 * </ul>
 */
public class TenantPlanInfo {

    public UUID tenantId;
    public String tenantName;
    public String subdomain;
    public String currentPlan;
    public OffsetDateTime planStartedAt;
    public OffsetDateTime lastModified;

    public TenantPlanInfo() {
    }

    public TenantPlanInfo(UUID tenantId, String tenantName, String subdomain, String currentPlan,
            OffsetDateTime planStartedAt, OffsetDateTime lastModified) {
        this.tenantId = tenantId;
        this.tenantName = tenantName;
        this.subdomain = subdomain;
        this.currentPlan = currentPlan;
        this.planStartedAt = planStartedAt;
        this.lastModified = lastModified;
    }
}
