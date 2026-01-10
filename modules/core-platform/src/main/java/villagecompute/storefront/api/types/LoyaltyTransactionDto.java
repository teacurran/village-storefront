package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for loyalty transaction information.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty transaction API responses</li>
 * <li>Entity: {@link villagecompute.storefront.data.models.LoyaltyTransaction}</li>
 * </ul>
 */
public class LoyaltyTransactionDto {

    public UUID id;
    public UUID memberId;
    public UUID orderId;
    public Integer pointsDelta;
    public Integer balanceAfter;
    public String transactionType;
    public String reason;
    public String source;
    public OffsetDateTime expiresAt;
    public OffsetDateTime createdAt;
}
