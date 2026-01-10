package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Feature flag DTO with governance metadata.
 *
 * <p>
 * Exposed via platform admin API and consumed by CLI tool and admin UI.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Feature flag governance</li>
 * <li>Architecture: 06_UI_UX_Architecture.md Section 4.9</li>
 * </ul>
 */
public class FeatureFlagDto {

    public UUID id;
    public UUID tenantId; // null for global flags
    public String flagKey;
    public Boolean enabled;
    public String config;

    // Governance metadata
    public String owner;
    public String riskLevel; // LOW, MEDIUM, HIGH, CRITICAL
    public Integer reviewCadenceDays;
    public OffsetDateTime expiryDate;
    public OffsetDateTime lastReviewedAt;
    public String description;
    public String rollbackInstructions;

    // Computed fields
    public Boolean stale;
    public String staleReason;

    // Timestamps
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
}
