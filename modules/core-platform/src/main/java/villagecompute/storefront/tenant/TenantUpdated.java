package villagecompute.storefront.tenant;

import villagecompute.storefront.data.models.Tenant;

/**
 * CDI event fired when a tenant record is updated. Triggers cache invalidation to ensure consistent tenant resolution.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Cache Coherence)</li>
 * <li>Task I1.T5: Tenant Access Gateway Prototype</li>
 * </ul>
 *
 * @param tenant
 *            updated tenant entity
 * @see TenantCacheInvalidator
 */
public record TenantUpdated(Tenant tenant) {

    public TenantUpdated {
        if (tenant == null) {
            throw new IllegalArgumentException("tenant cannot be null");
        }
    }
}
