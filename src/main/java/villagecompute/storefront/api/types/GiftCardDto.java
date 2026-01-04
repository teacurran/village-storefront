package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * DTO representing gift card details for admin APIs.
 */
public class GiftCardDto {
    public Long id;
    public String code;
    public BigDecimal initialBalance;
    public BigDecimal currentBalance;
    public String currency;
    public String status;
    public UUID purchaserUserId;
    public String purchaserEmail;
    public String recipientEmail;
    public String recipientName;
    public String personalMessage;
    public OffsetDateTime issuedAt;
    public OffsetDateTime activatedAt;
    public OffsetDateTime expiresAt;
    public OffsetDateTime fullyRedeemedAt;
    public Long sourceOrderId;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
