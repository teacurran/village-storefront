package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ConsignmentItemDto for consignment item API responses.
 *
 * <p>
 * Represents a product item under consignment with commission tracking.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: ConsignmentItemDto component schema</li>
 * <li>Endpoint: GET /admin/consignments</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
public class ConsignmentItemDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("productId")
    private UUID productId;

    @JsonProperty("productName")
    private String productName;

    @JsonProperty("consignorId")
    private UUID consignorId;

    @JsonProperty("consignorName")
    private String consignorName;

    @JsonProperty("commissionRate")
    private BigDecimal commissionRate;

    @JsonProperty("status")
    private String status;

    @JsonProperty("soldAt")
    private OffsetDateTime soldAt;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public UUID getConsignorId() {
        return consignorId;
    }

    public void setConsignorId(UUID consignorId) {
        this.consignorId = consignorId;
    }

    public String getConsignorName() {
        return consignorName;
    }

    public void setConsignorName(String consignorName) {
        this.consignorName = consignorName;
    }

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

    public OffsetDateTime getSoldAt() {
        return soldAt;
    }

    public void setSoldAt(OffsetDateTime soldAt) {
        this.soldAt = soldAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
