package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * AddIntakeBatchItemsRequest for adding items to an intake batch.
 *
 * <p>
 * Request payload for POST /admin/consignors/{id}/intake-batches/{batchId}/items.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Batch intake item requests</li>
 * </ul>
 */
public class AddIntakeBatchItemsRequest {

    @JsonProperty("items")
    @NotEmpty(
            message = "At least one item is required")
    @Valid
    private List<IntakeItemDto> items;

    public List<IntakeItemDto> getItems() {
        return items;
    }

    public void setItems(List<IntakeItemDto> items) {
        this.items = items;
    }

    /**
     * Intake item DTO for batch intake.
     */
    public static class IntakeItemDto {

        @JsonProperty("productName")
        @NotNull(
                message = "Product name is required")
        private String productName;

        @JsonProperty("description")
        private String description;

        @JsonProperty("sku")
        private String sku;

        @JsonProperty("barcode")
        private String barcode;

        @JsonProperty("price")
        @NotNull(
                message = "Price is required")
        private BigDecimal price;

        @JsonProperty("compareAtPrice")
        private BigDecimal compareAtPrice;

        @JsonProperty("costBasis")
        private BigDecimal costBasis;

        @JsonProperty("commissionRate")
        private BigDecimal commissionRate;

        @JsonProperty("quantity")
        private Integer quantity;

        @JsonProperty("variantTitle")
        private String variantTitle;

        @JsonProperty("metadata")
        private Map<String, Object> metadata;

        // Getters and setters

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSku() {
            return sku;
        }

        public void setSku(String sku) {
            this.sku = sku;
        }

        public String getBarcode() {
            return barcode;
        }

        public void setBarcode(String barcode) {
            this.barcode = barcode;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public BigDecimal getCompareAtPrice() {
            return compareAtPrice;
        }

        public void setCompareAtPrice(BigDecimal compareAtPrice) {
            this.compareAtPrice = compareAtPrice;
        }

        public BigDecimal getCostBasis() {
            return costBasis;
        }

        public void setCostBasis(BigDecimal costBasis) {
            this.costBasis = costBasis;
        }

        public BigDecimal getCommissionRate() {
            return commissionRate;
        }

        public void setCommissionRate(BigDecimal commissionRate) {
            this.commissionRate = commissionRate;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public String getVariantTitle() {
            return variantTitle;
        }

        public void setVariantTitle(String variantTitle) {
            this.variantTitle = variantTitle;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public void setMetadata(Map<String, Object> metadata) {
            this.metadata = metadata;
        }
    }
}
