package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for loyalty member information.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty member API responses</li>
 * <li>Entity: {@link villagecompute.storefront.data.models.LoyaltyMember}</li>
 * </ul>
 */
public class LoyaltyMemberDto {

    public UUID id;
    public UUID userId;
    public UUID programId;
    public Integer pointsBalance;
    public Integer lifetimePointsEarned;
    public Integer lifetimePointsRedeemed;
    public String currentTier;
    public OffsetDateTime tierUpdatedAt;
    public String status;
    public OffsetDateTime enrolledAt;
}
