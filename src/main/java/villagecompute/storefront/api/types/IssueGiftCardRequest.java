package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for issuing a new gift card (admin API).
 */
public class IssueGiftCardRequest {

    @NotNull(
            message = "Initial balance is required")
    @DecimalMin(
            value = "0.01",
            message = "Initial balance must be positive")
    public BigDecimal initialBalance;

    @Size(
            min = 3,
            max = 3,
            message = "Currency must be ISO 4217 code")
    public String currency = "USD";

    public UUID purchaserUserId;
    public String purchaserEmail;
    public String recipientEmail;
    public String recipientName;
    public String personalMessage;
    public OffsetDateTime expiresAt;
    public Long sourceOrderId;
}
