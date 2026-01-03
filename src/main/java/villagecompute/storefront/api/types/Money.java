package villagecompute.storefront.api.types;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Money value object for API responses matching OpenAPI Money schema.
 *
 * <p>
 * Represents monetary amounts as string to avoid floating-point precision issues. Always includes currency code.
 *
 * <p>
 * References:
 * <ul>
 * <li>OpenAPI: Money component schema</li>
 * <li>Standards: ISO 4217 currency codes</li>
 * </ul>
 */
public class Money {

    @JsonProperty("amount")
    private String amount;

    @JsonProperty("currency")
    private String currency;

    public Money() {
    }

    public Money(String amount, String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public Money(BigDecimal amount, String currency) {
        this.amount = amount.toPlainString();
        this.currency = currency;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public BigDecimal getAmountAsDecimal() {
        return new BigDecimal(amount);
    }
}
