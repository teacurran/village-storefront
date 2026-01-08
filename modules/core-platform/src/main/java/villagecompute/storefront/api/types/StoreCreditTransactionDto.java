package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * DTO for store credit ledger entries.
 */
public class StoreCreditTransactionDto {
    public Long id;
    public Long accountId;
    public Long orderId;
    public Long giftCardId;
    public BigDecimal amount;
    public String transactionType;
    public BigDecimal balanceAfter;
    public String reason;
    public Long posDeviceId;
    public OffsetDateTime createdAt;
}
