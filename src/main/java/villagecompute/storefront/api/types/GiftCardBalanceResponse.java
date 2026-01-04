package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Response DTO describing a gift card's balance and lifecycle state.
 */
public class GiftCardBalanceResponse {
    public BigDecimal currentBalance;
    public String currency;
    public String status;
    public OffsetDateTime expiresAt;
}
