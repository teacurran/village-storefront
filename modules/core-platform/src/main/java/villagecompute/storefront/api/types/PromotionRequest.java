package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for creating or updating promotions.
 *
 * <p>
 * Used by {@code POST /admin/promotions} and {@code PATCH /admin/promotions/{id}} endpoints. All fields optional for
 * PATCH, required fields enforced for POST.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Promotion API DTOs</li>
 * <li>OpenAPI: PromotionRequest schema</li>
 * </ul>
 */
public class PromotionRequest {

    @JsonProperty("code")
    @NotBlank(
            message = "Promotion code is required")
    @Size(
            max = 50,
            message = "Code must not exceed 50 characters")
    @Pattern(
            regexp = "^[A-Z0-9_-]+$",
            message = "Code must contain only uppercase letters, numbers, hyphens, and underscores")
    private String code;

    @JsonProperty("discountType")
    @NotBlank(
            message = "Discount type is required")
    private String discountType; // PERCENTAGE, FIXED_AMOUNT, FREE_SHIPPING

    @JsonProperty("discountValue")
    @NotNull(
            message = "Discount value is required")
    @Positive(
            message = "Discount value must be positive")
    private BigDecimal discountValue;

    @JsonProperty("currency")
    @Size(
            max = 3,
            min = 3,
            message = "Currency must be 3 characters")
    private String currency;

    @JsonProperty("minimumOrderAmount")
    private Money minimumOrderAmount;

    @JsonProperty("maximumDiscountAmount")
    private Money maximumDiscountAmount;

    @JsonProperty("usageLimit")
    private Integer usageLimit;

    @JsonProperty("perCustomerLimit")
    private Integer perCustomerLimit;

    @JsonProperty("startDate")
    private OffsetDateTime startDate;

    @JsonProperty("expiryDate")
    private OffsetDateTime expiryDate;

    @JsonProperty("description")
    @Size(
            max = 500,
            message = "Description must not exceed 500 characters")
    private String description;

    public PromotionRequest() {
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
