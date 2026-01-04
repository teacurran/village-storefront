package villagecompute.storefront.api.types;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request payload describing a loyalty tier threshold used when configuring a program.
 */
public class LoyaltyTierConfigRequest {

    @NotBlank(
            message = "Tier name is required")
    public String name;

    @Min(
            value = 0,
            message = "Tier minPoints must be zero or greater")
    public Integer minPoints;

    @DecimalMin(
            value = "0.0",
            inclusive = false,
            message = "Tier multiplier must be greater than zero")
    public BigDecimal multiplier;
}
