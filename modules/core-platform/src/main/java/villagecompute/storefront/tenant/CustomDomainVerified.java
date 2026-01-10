package villagecompute.storefront.tenant;

import villagecompute.storefront.data.models.CustomDomain;

/**
 * CDI event fired when a custom domain is verified or modified. Triggers cache invalidation for the affected domain.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Custom Domain Resolution)</li>
 * <li>Task I1.T5: Tenant Access Gateway Prototype</li>
 * </ul>
 *
 * @param customDomain
 *            verified or updated custom domain entity
 * @see TenantCacheInvalidator
 */
public record CustomDomainVerified(CustomDomain customDomain) {

    public CustomDomainVerified {
        if (customDomain == null) {
            throw new IllegalArgumentException("customDomain cannot be null");
        }
    }
}
