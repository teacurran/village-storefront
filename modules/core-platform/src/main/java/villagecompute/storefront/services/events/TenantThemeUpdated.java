package villagecompute.storefront.services.events;

import java.util.UUID;

/**
 * CDI event emitted when a tenant updates their storefront design tokens.
 *
 * <p>
 * Allows services such as {@link villagecompute.storefront.services.ThemeProvider} to invalidate caches whenever
 * tenant-branding data changes or when platform admins publish updated defaults.
 */
public record TenantThemeUpdated(UUID tenantId, String subdomain, boolean invalidateAllTenants) {

    public TenantThemeUpdated {
        if (!invalidateAllTenants && (subdomain == null || subdomain.isBlank())) {
            throw new IllegalArgumentException("subdomain must be provided unless invalidating all tenants");
        }
    }

    public TenantThemeUpdated(UUID tenantId, String subdomain) {
        this(tenantId, subdomain, false);
    }

    public static TenantThemeUpdated invalidateAll() {
        return new TenantThemeUpdated(null, null, true);
    }
}
