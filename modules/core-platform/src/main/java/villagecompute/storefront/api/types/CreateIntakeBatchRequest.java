package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CreateIntakeBatchRequest for creating a new intake batch.
 *
 * <p>
 * Request payload for POST /admin/consignors/{id}/intake-batches.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Batch intake implementation</li>
 * </ul>
 */
public class CreateIntakeBatchRequest {

    @JsonProperty("batchName")
    @NotBlank(
            message = "Batch name is required")
    private String batchName;

    @JsonProperty("notes")
    private String notes;

    public String getBatchName() {
        return batchName;
    }

    public void setBatchName(String batchName) {
        this.batchName = batchName;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
