package villagecompute.storefront.api.types;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for redeeming store credit during checkout/POS.
 */
public class RedeemStoreCreditRequest {

    @NotNull(
            message = "Amount is required")
    @DecimalMin(
            value = "0.01",
            message = "Amount must be positive")
    public BigDecimal amount;

    public Long orderId;
    public String idempotencyKey;
    public Long posDeviceId;
    public OffsetDateTime offlineSyncedAt;
}
