package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * InventoryTransferDto representing an inventory transfer between locations.
 *
 * <p>
 * Response DTO for transfer queries and creation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Inventory transfer DTO contract</li>
 * </ul>
 */
public class InventoryTransferDto {

    @JsonProperty("transferId")
    private UUID transferId;

    @JsonProperty("sourceLocationId")
    private UUID sourceLocationId;

    @JsonProperty("destinationLocationId")
    private UUID destinationLocationId;

    @JsonProperty("status")
    private String status;

    @JsonProperty("initiatedBy")
    private String initiatedBy;

    @JsonProperty("expectedArrivalDate")
    private OffsetDateTime expectedArrivalDate;

    @JsonProperty("carrier")
    private String carrier;

    @JsonProperty("trackingNumber")
    private String trackingNumber;

    @JsonProperty("shippingCost")
    private Integer shippingCost;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("lines")
    private List<TransferLineDto> lines;

    @JsonProperty("barcodeJobId")
    private UUID barcodeJobId;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    /**
     * TransferLineDto representing a line item in the transfer.
     */
    public static class TransferLineDto {

        @JsonProperty("variantId")
        private UUID variantId;

        @JsonProperty("quantity")
        private Integer quantity;

        @JsonProperty("receivedQuantity")
        private Integer receivedQuantity;

        @JsonProperty("notes")
        private String notes;

        // Getters and setters

        public UUID getVariantId() {
            return variantId;
        }

        public void setVariantId(UUID variantId) {
            this.variantId = variantId;
        }

        public Integer getQuantity() {
            return quantity;
        }

        public void setQuantity(Integer quantity) {
            this.quantity = quantity;
        }

        public Integer getReceivedQuantity() {
            return receivedQuantity;
        }

        public void setReceivedQuantity(Integer receivedQuantity) {
            this.receivedQuantity = receivedQuantity;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    // Getters and setters

    public UUID getTransferId() {
        return transferId;
    }

    public void setTransferId(UUID transferId) {
        this.transferId = transferId;
    }

    public UUID getSourceLocationId() {
        return sourceLocationId;
    }

    public void setSourceLocationId(UUID sourceLocationId) {
        this.sourceLocationId = sourceLocationId;
    }

    public UUID getDestinationLocationId() {
        return destinationLocationId;
    }

    public void setDestinationLocationId(UUID destinationLocationId) {
        this.destinationLocationId = destinationLocationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(String initiatedBy) {
        this.initiatedBy = initiatedBy;
    }

    public OffsetDateTime getExpectedArrivalDate() {
        return expectedArrivalDate;
    }

    public void setExpectedArrivalDate(OffsetDateTime expectedArrivalDate) {
        this.expectedArrivalDate = expectedArrivalDate;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public void setTrackingNumber(String trackingNumber) {
        this.trackingNumber = trackingNumber;
    }

    public Integer getShippingCost() {
        return shippingCost;
    }

    public void setShippingCost(Integer shippingCost) {
        this.shippingCost = shippingCost;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<TransferLineDto> getLines() {
        return lines;
    }

    public void setLines(List<TransferLineDto> lines) {
        this.lines = lines;
    }

    public UUID getBarcodeJobId() {
        return barcodeJobId;
    }

    public void setBarcodeJobId(UUID barcodeJobId) {
        this.barcodeJobId = barcodeJobId;
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
