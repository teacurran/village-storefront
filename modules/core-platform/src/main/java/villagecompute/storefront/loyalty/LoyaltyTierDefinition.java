package villagecompute.storefront.loyalty;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Tier configuration definition parsed from {@code LoyaltyProgram.tierConfig}.
 */
@JsonIgnoreProperties(
        ignoreUnknown = true)
public class LoyaltyTierDefinition {

    private String name;
    private Integer minPoints;
    private BigDecimal multiplier;

    public LoyaltyTierDefinition() {
    }

    public LoyaltyTierDefinition(String name, Integer minPoints, BigDecimal multiplier) {
        this.name = name;
        this.minPoints = minPoints;
        this.multiplier = multiplier;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMinPoints() {
        return minPoints;
    }

    public void setMinPoints(Integer minPoints) {
        this.minPoints = minPoints;
    }

    public BigDecimal getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(BigDecimal multiplier) {
        this.multiplier = multiplier;
    }

    public BigDecimal multiplierOrDefault() {
        return multiplier != null ? multiplier : BigDecimal.ONE;
    }
}
