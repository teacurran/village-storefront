package villagecompute.storefront.tenant;

/**
 * CDI event fired when a custom domain is removed or unverified. Triggers cache invalidation for the affected domain.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 2: Custom Domain Resolution)</li>
 * <li>Task I1.T5: Tenant Access Gateway Prototype</li>
 * </ul>
 *
 * @param domain
 *            domain that was removed or unverified
 * @param tenantId
 *            tenant ID that owned the domain
 * @see TenantCacheInvalidator
 */
public record CustomDomainRemoved(String domain, java.util.UUID tenantId) {

    public CustomDomainRemoved {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("domain cannot be null or blank");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId cannot be null");
        }
    }
}
