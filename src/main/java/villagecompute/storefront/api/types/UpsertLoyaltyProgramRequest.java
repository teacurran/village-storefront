package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for creating or updating a loyalty program configuration.
 */
public class UpsertLoyaltyProgramRequest {

    @NotBlank(
            message = "Program name is required")
    public String name;

    public String description;

    public Boolean enabled = Boolean.TRUE;

    @NotNull(
            message = "Points per dollar is required")
    @DecimalMin(
            value = "0.0",
            inclusive = false,
            message = "Points per dollar must be greater than zero")
    public BigDecimal pointsPerDollar;

    @NotNull(
            message = "Redemption value per point is required")
    @DecimalMin(
            value = "0.0",
            inclusive = false,
            message = "Redemption value per point must be greater than zero")
    public BigDecimal redemptionValuePerPoint;

    @NotNull(
            message = "Minimum redemption points are required")
    @Min(
            value = 1,
            message = "Minimum redemption points must be at least 1")
    public Integer minRedemptionPoints;

    public Integer maxRedemptionPoints;

    public Integer pointsExpirationDays;

    @Valid
    @NotEmpty(
            message = "At least one tier definition is required")
    public List<LoyaltyTierConfigRequest> tiers;

    public Map<String, Object> metadata;
}
