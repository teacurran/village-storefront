package villagecompute.storefront.api.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for partially updating an order (admin operation).
 *
 * <p>
 * Maps to the OpenAPI `UpdateOrderRequest` schema. All fields are optional - only provided fields are updated.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order admin API DTOs</li>
 * <li>OpenAPI: UpdateOrderRequest schema</li>
 * </ul>
 */
public class UpdateOrderRequest {

    @JsonProperty("status")
    private String status;

    @JsonProperty("notes")
    private String notes;

    @JsonProperty("tags")
    private List<String> tags;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }
}
