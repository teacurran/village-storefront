package villagecompute.storefront.tenant;

import java.util.UUID;

/**
 * Immutable tenant information holder. Cached by {@link TenantResolutionFilter} to minimize database lookups.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Tenant Resolution Flow)</li>
 * <li>ERD: datamodel_erd.puml (tenants, custom_domains tables)</li>
 * </ul>
 *
 * @param tenantId
 *            Unique tenant identifier (from tenants.id)
 * @param subdomain
 *            Tenant subdomain (storename.villagecompute.com)
 * @param name
 *            Display name of the tenant store
 * @param status
 *            Tenant status (active, suspended, deleted)
 */
public record TenantInfo(UUID tenantId, String subdomain, String name, String status) {

    /**
     * Create tenant info with required fields.
     *
     * @param tenantId
     *            tenant UUID
     * @param subdomain
     *            tenant subdomain
     * @param name
     *            tenant display name
     * @param status
     *            tenant status
     */
    public TenantInfo {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
        if (subdomain == null || subdomain.isBlank()) {
            throw new IllegalArgumentException("subdomain cannot be null or blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or blank");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status cannot be null or blank");
        }
    }

    /**
     * Check if tenant is active.
     *
     * @return true if status is "active"
     */
    public boolean isActive() {
        return "active".equals(status);
    }

    /**
     * Check if tenant is suspended.
     *
     * @return true if status is "suspended"
     */
    public boolean isSuspended() {
        return "suspended".equals(status);
    }
}
