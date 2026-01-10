package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Promotion data transfer object for API responses.
 *
 * <p>
 * Represents a promotional discount code with its configuration and usage statistics. Matches the OpenAPI Promotion
 * schema.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: Promotion component schema</li>
 * <li>Task I3.T2: Promotion API DTOs</li>
 * </ul>
 */
public class PromotionDto {

    @JsonProperty("id")
    private UUID id;

    @JsonProperty("code")
    private String code;

    @JsonProperty("discountType")
    private String discountType; // PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING

    @JsonProperty("discountValue")
    private BigDecimal discountValue;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("minimumOrderAmount")
    private Money minimumOrderAmount;

    @JsonProperty("maximumDiscountAmount")
    private Money maximumDiscountAmount;

    @JsonProperty("usageLimit")
    private Integer usageLimit;

    @JsonProperty("usageCount")
    private Integer usageCount;

    @JsonProperty("perCustomerLimit")
    private Integer perCustomerLimit;

    @JsonProperty("startDate")
    private OffsetDateTime startDate;

    @JsonProperty("expiryDate")
    private OffsetDateTime expiryDate;

    @JsonProperty("status")
    private String status; // ACTIVE, INACTIVE, EXPIRED

    @JsonProperty("description")
    private String description;

    @JsonProperty("createdAt")
    private OffsetDateTime createdAt;

    @JsonProperty("updatedAt")
    private OffsetDateTime updatedAt;

    public PromotionDto() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDiscountType() {
        return discountType;
    }

    public void setDiscountType(String discountType) {
        this.discountType = discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
    }

    public void setDiscountValue(BigDecimal discountValue) {
        this.discountValue = discountValue;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public Money getMinimumOrderAmount() {
        return minimumOrderAmount;
    }

    public void setMinimumOrderAmount(Money minimumOrderAmount) {
        this.minimumOrderAmount = minimumOrderAmount;
    }

    public Money getMaximumDiscountAmount() {
        return maximumDiscountAmount;
    }

    public void setMaximumDiscountAmount(Money maximumDiscountAmount) {
        this.maximumDiscountAmount = maximumDiscountAmount;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public void setUsageLimit(Integer usageLimit) {
        this.usageLimit = usageLimit;
    }

    public Integer getUsageCount() {
        return usageCount;
    }

    public void setUsageCount(Integer usageCount) {
        this.usageCount = usageCount;
    }

    public Integer getPerCustomerLimit() {
        return perCustomerLimit;
    }

    public void setPerCustomerLimit(Integer perCustomerLimit) {
        this.perCustomerLimit = perCustomerLimit;
    }

    public OffsetDateTime getStartDate() {
        return startDate;
    }

    public void setStartDate(OffsetDateTime startDate) {
        this.startDate = startDate;
    }

    public OffsetDateTime getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(OffsetDateTime expiryDate) {
        this.expiryDate = expiryDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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
