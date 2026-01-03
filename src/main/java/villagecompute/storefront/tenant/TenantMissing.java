package villagecompute.storefront.tenant;

/**
 * CDI event fired when tenant resolution fails. Subscribers can perform logging, metrics, or custom error handling.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 3.2.1: Tenant Access Gateway)</li>
 * <li>Architecture Overview Section 3.2.1: CDI events for downstream modules</li>
 * </ul>
 *
 * @param hostname
 *            original Host header value that failed resolution
 * @param reason
 *            reason for failure (e.g., "no_custom_domain", "no_subdomain_match", "missing_host_header")
 */
public record TenantMissing(String hostname, String reason) {

    public TenantMissing {
        if (hostname == null || hostname.isBlank()) {
            throw new IllegalArgumentException("hostname cannot be null or blank");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be null or blank");
        }
    }
}
