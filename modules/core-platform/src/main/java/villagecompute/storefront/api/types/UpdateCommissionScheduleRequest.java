package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request payload for updating an existing commission schedule.
 */
public class UpdateCommissionScheduleRequest {

    @JsonProperty("categoryId")
    private UUID categoryId;

    @JsonProperty("commissionRate")
    @NotNull @DecimalMin("0.00")
    @DecimalMax("100.00")
    private BigDecimal commissionRate;

    @JsonProperty("effectiveFrom")
    @NotNull private LocalDate effectiveFrom;

    @JsonProperty("effectiveUntil")
    private LocalDate effectiveUntil;

    @JsonProperty("priority")
    private Integer priority;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("metadata")
    private String metadata;

    @JsonProperty("status")
    private String status;

    public UUID getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(UUID categoryId) {
        this.categoryId = categoryId;
    }

    public BigDecimal getCommissionRate() {
        return commissionRate;
    }

    public void setCommissionRate(BigDecimal commissionRate) {
        this.commissionRate = commissionRate;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDate getEffectiveUntil() {
        return effectiveUntil;
    }

    public void setEffectiveUntil(LocalDate effectiveUntil) {
        this.effectiveUntil = effectiveUntil;
    }

    public Integer getPriority() {
        return priority;
    }

    public void setPriority(Integer priority) {
        this.priority = priority;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
