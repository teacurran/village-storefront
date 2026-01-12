package villagecompute.storefront.api.types;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for updating a consignment item (commission/cost basis/status).
 */
public class UpdateConsignmentItemRequest {

    @JsonProperty("commissionRate")
    @DecimalMin(
            value = "0.00",
            message = "Commission rate must be at least 0%")
    @DecimalMax(
            value = "100.00",
            message = "Commission rate cannot exceed 100%")
    private BigDecimal commissionRate;

    @JsonProperty("status")
    private String status;

    @JsonProperty("costBasis")
    private BigDecimal costBasis;

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getCostBasis() {
        return costBasis;
    }

    public void setCostBasis(BigDecimal costBasis) {
        this.costBasis = costBasis;
    }
}
