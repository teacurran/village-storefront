package villagecompute.storefront.api.types;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for redeeming loyalty points.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty redemption API request</li>
 * <li>ADR-003: Idempotent redemption operations</li>
 * </ul>
 */
public class RedeemPointsRequest {

    @NotNull(
            message = "Points to redeem is required")
    @Min(
            value = 1,
            message = "Points to redeem must be positive")
    public Integer pointsToRedeem;

    /**
     * Optional fallback idempotency key (header `X-Idempotency-Key` takes precedence).
     */
    public String idempotencyKey;
}
