package villagecompute.storefront.compliance.api.types;

import java.util.UUID;

/**
 * Request DTO for recording marketing consent.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (consent management)</li>
 * </ul>
 */
public record RecordConsentRequest(UUID customerId, String channel, boolean consented, String consentSource,
        String consentMethod, String ipAddress, String userAgent, String notes) {
}
