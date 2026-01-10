package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO for loyalty program information.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty program API responses</li>
 * <li>Entity: {@link villagecompute.storefront.data.models.LoyaltyProgram}</li>
 * </ul>
 */
public class LoyaltyProgramDto {

    public UUID id;
    public String name;
    public String description;
    public Boolean enabled;
    public BigDecimal pointsPerDollar;
    public BigDecimal redemptionValuePerPoint;
    public Integer minRedemptionPoints;
    public Integer maxRedemptionPoints;
    public Integer pointsExpirationDays;
    public String tierConfig;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
