package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * DTO describing individual gift card ledger entries.
 */
public class GiftCardTransactionDto {
    public Long id;
    public Long giftCardId;
    public Long orderId;
    public BigDecimal amount;
    public String transactionType;
    public BigDecimal balanceAfter;
    public String reason;
    public Long posDeviceId;
    public OffsetDateTime createdAt;
}
