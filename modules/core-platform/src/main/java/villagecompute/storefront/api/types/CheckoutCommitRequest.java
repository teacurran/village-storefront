package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request DTO for completing checkout and creating an order.
 *
 * <p>
 * Maps to the OpenAPI `CheckoutCommitRequest` schema.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Checkout API DTOs</li>
 * <li>OpenAPI: CheckoutCommitRequest schema</li>
 * </ul>
 */
public class CheckoutCommitRequest {

    @JsonProperty("cartId")
    @NotBlank(
            message = "Cart ID is required")
    private String cartId;

    @JsonProperty("customerEmail")
    @NotBlank(
            message = "Customer email is required")
    @Email(
            message = "Customer email must be valid")
    private String customerEmail;

    @JsonProperty("shippingAddress")
    @NotBlank(
            message = "Shipping address is required")
    private String shippingAddress;

    @JsonProperty("billingAddress")
    @NotBlank(
            message = "Billing address is required")
    private String billingAddress;

    @JsonProperty("shippingAmount")
    private BigDecimal shippingAmount;

    @JsonProperty("taxAmount")
    private BigDecimal taxAmount;

    @JsonProperty("currency")
    private String currency;

    @JsonProperty("paymentMethodId")
    @NotBlank(
            message = "Payment method ID is required")
    private String paymentMethodId;

    @JsonProperty("giftCardCodes")
    private List<String> giftCardCodes;

    @JsonProperty("useStoreCredit")
    private Boolean useStoreCredit;

    public String getCartId() {
        return cartId;
    }

    public void setCartId(String cartId) {
        this.cartId = cartId;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
    }

    public String getShippingAddress() {
        return shippingAddress;
    }

    public void setShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
    }

    public String getBillingAddress() {
        return billingAddress;
    }

    public void setBillingAddress(String billingAddress) {
        this.billingAddress = billingAddress;
    }

    public BigDecimal getShippingAmount() {
        return shippingAmount;
    }

    public void setShippingAmount(BigDecimal shippingAmount) {
        this.shippingAmount = shippingAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public void setTaxAmount(BigDecimal taxAmount) {
        this.taxAmount = taxAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getPaymentMethodId() {
        return paymentMethodId;
    }

    public void setPaymentMethodId(String paymentMethodId) {
        this.paymentMethodId = paymentMethodId;
    }

    public List<String> getGiftCardCodes() {
        return giftCardCodes;
    }

    public void setGiftCardCodes(List<String> giftCardCodes) {
        this.giftCardCodes = giftCardCodes;
    }

    public Boolean getUseStoreCredit() {
        return useStoreCredit;
    }

    public void setUseStoreCredit(Boolean useStoreCredit) {
        this.useStoreCredit = useStoreCredit;
    }
}
