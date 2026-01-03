package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CreateInventoryTransferRequest for creating a new inventory transfer.
 *
 * <p>
 * Request payload for POST /api/v1/admin/inventory/transfers.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Inventory transfer DTO contract</li>
 * <li>OpenAPI: CreateInventoryTransferRequest component schema</li>
 * </ul>
 */
public class CreateInventoryTransferRequest {

    @JsonProperty("sourceLocationId")
    @NotNull(
            message = "Source location ID is required")
    private UUID sourceLocationId;

    @JsonProperty("destinationLocationId")
    @NotNull(
            message = "Destination location ID is required")
    private UUID destinationLocationId;

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
    @NotEmpty(
            message = "At least one transfer line is required")
    @Valid
    private List<TransferLineRequest> lines;

    /**
     * TransferLineRequest representing a line item in the transfer.
     */
    public static class TransferLineRequest {

        @JsonProperty("variantId")
        @NotNull(
                message = "Variant ID is required")
        private UUID variantId;

        @JsonProperty("quantity")
        @NotNull(
                message = "Quantity is required")
        private Integer quantity;

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

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    // Getters and setters

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

    public List<TransferLineRequest> getLines() {
        return lines;
    }

    public void setLines(List<TransferLineRequest> lines) {
        this.lines = lines;
    }
}
