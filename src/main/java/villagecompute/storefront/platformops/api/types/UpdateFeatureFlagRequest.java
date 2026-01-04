package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;

/**
 * Request payload for updating feature flag state and governance metadata.
 *
 * <p>
 * All fields are optional - only provided fields will be updated.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Feature flag governance</li>
 * </ul>
 */
public class UpdateFeatureFlagRequest {

    public Boolean enabled;
    public String owner;
    public String riskLevel;
    public Integer reviewCadenceDays;
    public OffsetDateTime expiryDate;
    public String description;
    public String rollbackInstructions;
    public Boolean markReviewed; // If true, sets lastReviewedAt to now
    public String reason; // Audit trail justification
}
