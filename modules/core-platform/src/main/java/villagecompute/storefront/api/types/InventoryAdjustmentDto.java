package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * InventoryAdjustmentDto representing a manual inventory adjustment.
 *
 * <p>
 * Response DTO for adjustment queries and creation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow (adjustments)</li>
 * <li>Entity: InventoryAdjustment</li>
 * </ul>
 */
public class InventoryAdjustmentDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("variantId")
    private UUID variantId;

    @JsonProperty("locationId")
    private UUID locationId;

    @JsonProperty("quantityChange")
    private Integer quantityChange;

    @JsonProperty("quantityBefore")
    private Integer quantityBefore;

    @JsonProperty("quantityAfter")
    private Integer quantityAfter;

    @JsonProperty("reason")
    private String reason;

    @JsonProperty("adjustedBy")
    private String adjustedBy;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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

    public Integer getQuantityBefore() {
        return quantityBefore;
    }

    public void setQuantityBefore(Integer quantityBefore) {
        this.quantityBefore = quantityBefore;
    }

    public Integer getQuantityAfter() {
        return quantityAfter;
    }

    public void setQuantityAfter(Integer quantityAfter) {
        this.quantityAfter = quantityAfter;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
