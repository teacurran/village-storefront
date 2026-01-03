package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * CreateConsignorRequest for creating a new consignor.
 *
 * <p>
 * Request payload for POST /admin/consignors.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: CreateConsignorRequest component schema</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
public class CreateConsignorRequest {

    @JsonProperty("name")
    @NotBlank(
            message = "Consignor name is required")
    private String name;

    @JsonProperty("contactInfo")
    private String contactInfo;

    @JsonProperty("payoutSettings")
    private String payoutSettings;

    // Getters and setters

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContactInfo() {
        return contactInfo;
    }

    public void setContactInfo(String contactInfo) {
        this.contactInfo = contactInfo;
    }

    public String getPayoutSettings() {
        return payoutSettings;
    }

    public void setPayoutSettings(String payoutSettings) {
        this.payoutSettings = payoutSettings;
    }
}
