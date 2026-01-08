package villagecompute.storefront.tenant;

/**
 * CDI event fired when tenant resolution succeeds. Subscribers can perform tenant-specific initialization or logging.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 3.2.1: Tenant Access Gateway)</li>
 * <li>Architecture Overview Section 3.2.1: CDI events for downstream modules</li>
 * </ul>
 *
 * @param tenantInfo
 *            resolved tenant information
 * @param hostname
 *            original Host header value
 */
public record TenantResolved(TenantInfo tenantInfo, String hostname) {

    public TenantResolved {
        if (tenantInfo == null) {
            throw new IllegalArgumentException("tenantInfo cannot be null");
        }
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname cannot be null or blank");
        }
    }
}
