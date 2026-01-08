package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Store directory entry DTO for platform admin console.
 *
 * <p>
 * Represents a tenant/store in the platform directory listing with key metadata for management and support.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console (store directory)</li>
 * </ul>
 */
public class StoreDirectoryEntry {

    public UUID id;
    public String subdomain;
    public String name;
    public String status; // 'active', 'suspended', 'trial', 'deleted'
    public Integer userCount;
    public Integer activeUserCount;
    public OffsetDateTime createdAt;
    public OffsetDateTime lastActivityAt;
    public String plan; // 'free', 'basic', 'pro', 'enterprise'
    public Boolean customDomainConfigured;

    public StoreDirectoryEntry() {
    }

    public StoreDirectoryEntry(UUID id, String subdomain, String name, String status, Integer userCount,
            Integer activeUserCount, OffsetDateTime createdAt, OffsetDateTime lastActivityAt, String plan,
            Boolean customDomainConfigured) {
        this.id = id;
        this.subdomain = subdomain;
        this.name = name;
        this.status = status;
        this.userCount = userCount;
        this.activeUserCount = activeUserCount;
        this.createdAt = createdAt;
        this.lastActivityAt = lastActivityAt;
        this.plan = plan;
        this.customDomainConfigured = customDomainConfigured;
    }
}
