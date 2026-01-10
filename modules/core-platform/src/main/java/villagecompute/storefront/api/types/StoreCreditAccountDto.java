package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO describing store credit account state for a customer.
 */
public class StoreCreditAccountDto {
    public Long id;
    public UUID userId;
    public BigDecimal balance;
    public String currency;
    public String status;
    public String notes;
    public String metadata;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
