package villagecompute.storefront.tenant;

import java.util.Objects;
import java.util.UUID;

import villagecompute.storefront.data.models.Tenant;

/**
 * Thread-local holder for current tenant context. Automatically populated by {@link TenantResolutionFilter}.
 *
 * <p>
 * <strong>CRITICAL:</strong> All repository queries MUST filter by {@link #getCurrentTenantId()} to prevent
 * cross-tenant data leakage.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 4: Repository-Level Enforcement)</li>
 * <li>Architecture Overview Section 5: Multi-Tenancy & Data Isolation</li>
 * </ul>
 *
 * @see TenantResolutionFilter
 * @see TenantContextClearFilter
 */
public class TenantContext {

    private static final ThreadLocal<TenantInfo> CURRENT_TENANT = new ThreadLocal<>();

    /**
     * Set current tenant for this request thread. Called by {@link TenantResolutionFilter}.
     *
     * @param tenantInfo
     *            tenant information, must not be null
     * @throws NullPointerException
     *             if tenantInfo is null
     */
    public static void setCurrentTenant(TenantInfo tenantInfo) {
        Objects.requireNonNull(tenantInfo, "Tenant info cannot be null");
        Objects.requireNonNull(tenantInfo.tenantId(), "Tenant ID cannot be null");
        CURRENT_TENANT.set(tenantInfo);
    }

    /**
     * Convenience helper for background jobs that only know the tenant ID.
     *
     * <p>
     * Resolves the tenant record and seeds {@link TenantInfo} so repositories can continue enforcing tenant filters
     * without going through the HTTP resolution filter. Primarily used by scheduled jobs and integration tests.
     *
     * @param tenantId
     *            tenant UUID
     */
    public static void setCurrentTenantId(UUID tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID cannot be null");

        Tenant tenant = Tenant.findById(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("Tenant not found for id " + tenantId);
        }

        TenantInfo info = new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status);
        setCurrentTenant(info);
    }

    /**
     * Get current tenant ID for this request thread.
     *
     * @return tenant UUID
     * @throws IllegalStateException
     *             if no tenant context set (filter not executed)
     */
    public static UUID getCurrentTenantId() {
        TenantInfo info = CURRENT_TENANT.get();
        if (info == null) {
            throw new IllegalStateException("No tenant context available - TenantResolutionFilter not executed");
        }
        return info.tenantId();
    }

    /**
     * Get current tenant information for this request thread.
     *
     * @return tenant information
     * @throws IllegalStateException
     *             if no tenant context set (filter not executed)
     */
    public static TenantInfo getCurrentTenant() {
        TenantInfo info = CURRENT_TENANT.get();
        if (info == null) {
            throw new IllegalStateException("No tenant context available - TenantResolutionFilter not executed");
        }
        return info;
    }

    /**
     * Check if tenant context is set (useful for background jobs).
     *
     * @return true if tenant context is available, false otherwise
     */
    public static boolean hasContext() {
        return CURRENT_TENANT.get() != null;
    }

    /**
     * Clear tenant context. Called by {@link TenantContextClearFilter}. MUST be called in finally block to prevent
     * ThreadLocal leakage.
     */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
