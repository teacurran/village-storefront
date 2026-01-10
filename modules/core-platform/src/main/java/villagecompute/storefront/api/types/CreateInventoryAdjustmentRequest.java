package villagecompute.storefront.api.types;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CreateInventoryAdjustmentRequest for recording a manual inventory adjustment.
 *
 * <p>
 * Request payload for POST /api/v1/admin/inventory/adjustments.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow (adjustments)</li>
 * <li>OpenAPI: CreateInventoryAdjustmentRequest component schema</li>
 * </ul>
 */
public class CreateInventoryAdjustmentRequest {

    @JsonProperty("variantId")
    @NotNull(
            message = "Variant ID is required")
    private UUID variantId;

    @JsonProperty("locationId")
    @NotNull(
            message = "Location ID is required")
    private UUID locationId;

    @JsonProperty("quantityChange")
    @NotNull(
            message = "Quantity change is required")
    private Integer quantityChange;

    @JsonProperty("reason")
    @NotBlank(
            message = "Adjustment reason is required")
    private String reason;

    @JsonProperty("adjustedBy")
    @NotBlank(
            message = "Adjusted by is required")
    private String adjustedBy;

    @JsonProperty("notes")
    private String notes;

    // Getters and setters

    public UUID getVariantId() {
        return variantId;
    }

    public void setVariantId(UUID variantId) {
        this.variantId = variantId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public void setLocationId(UUID locationId) {
        this.locationId = locationId;
    }

    public Integer getQuantityChange() {
        return quantityChange;
    }

    public void setQuantityChange(Integer quantityChange) {
        this.quantityChange = quantityChange;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getAdjustedBy() {
        return adjustedBy;
    }

    public void setAdjustedBy(String adjustedBy) {
        this.adjustedBy = adjustedBy;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
