package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CreateInventoryLocationRequest for creating a new inventory location.
 *
 * <p>
 * Request payload for POST /api/v1/admin/inventory/locations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>OpenAPI: CreateInventoryLocationRequest component schema</li>
 * </ul>
 */
public class CreateInventoryLocationRequest {

    @JsonProperty("code")
    @NotBlank(
            message = "Location code is required")
    @Size(
            max = 100,
            message = "Location code must not exceed 100 characters")
    private String code;

    @JsonProperty("name")
    @NotBlank(
            message = "Location name is required")
    @Size(
            max = 255,
            message = "Location name must not exceed 255 characters")
    private String name;

    @JsonProperty("type")
    @Size(
            max = 50,
            message = "Location type must not exceed 50 characters")
    private String type;

    @JsonProperty("address")
    private String address;

    @JsonProperty("active")
    private Boolean active = true;

    // Getters and setters

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
