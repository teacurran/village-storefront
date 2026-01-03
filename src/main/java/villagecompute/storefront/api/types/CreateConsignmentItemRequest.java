package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CreateConsignmentItemRequest for intake of a consignment item.
 *
 * <p>
 * Request payload for POST /admin/consignments.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: CreateConsignmentItemRequest component schema</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
public class CreateConsignmentItemRequest {

    @JsonProperty("productId")
    @NotNull(
            message = "Product ID is required")
    private UUID productId;

    /**
     * Optional consignor identifier. When the admin API is invoked via `/admin/consignors/{consignorId}/items`, the
     * path parameter is the source of truth. Including the value here allows future bulk APIs, but it is not required
     * for the current endpoint.
     */
    @JsonProperty("consignorId")
    private UUID consignorId;

    @JsonProperty("commissionRate")
    @NotNull(
            message = "Commission rate is required")
    @DecimalMin(
            value = "0.00",
            message = "Commission rate must be at least 0%")
    @DecimalMax(
            value = "100.00",
            message = "Commission rate cannot exceed 100%")
    private BigDecimal commissionRate;

    // Getters and setters

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public UUID getConsignorId() {
        return consignorId;
    }

    public void setConsignorId(UUID consignorId) {
        this.consignorId = consignorId;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }
}
