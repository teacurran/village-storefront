package villagecompute.storefront.api.types;

import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for adjusting loyalty points (admin operation).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty admin adjustment API request</li>
 * </ul>
 */
public class AdjustPointsRequest {

    @NotNull(
            message = "Points is required")
    public Integer points;

    @NotNull(
            message = "Reason is required")
    public String reason;
}
