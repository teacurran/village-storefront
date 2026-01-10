package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * ConsignorDto for consignment vendor API responses.
 *
 * <p>
 * Represents a consignment vendor who provides items for sale.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: ConsignorDto component schema</li>
 * <li>Endpoint: GET /admin/consignors</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
public class ConsignorDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("contactInfo")
    private String contactInfo;

    @JsonProperty("payoutSettings")
    private String payoutSettings;

    @JsonProperty("status")
    private String status;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    // Getters and setters

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
