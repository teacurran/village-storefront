package villagecompute.storefront.compliance.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for marketing consent responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (consent management)</li>
 * </ul>
 */
public record MarketingConsentDto(UUID id, UUID customerId, String channel, boolean consented, String consentSource,
        String consentMethod, OffsetDateTime consentedAt) {
}
