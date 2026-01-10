package villagecompute.storefront.api.types;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for admin store credit adjustments.
 */
public class AdjustStoreCreditRequest {

    @NotNull(
            message = "Amount is required")
    public BigDecimal amount;

    @NotBlank(
            message = "Reason is required")
    public String reason;
}
