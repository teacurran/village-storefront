package villagecompute.storefront.platformops.api.types;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Report on stale feature flags for dashboards and automation.
 *
 * <p>
 * Consumed by Grafana dashboards and CLI stale detection commands.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Feature flag governance</li>
 * <li>Architecture: 05_Rationale_and_Future.md Section 4.1.12</li>
 * </ul>
 */
public class StaleFlagReport {

    public Integer expiredCount;
    public List<FeatureFlagDto> expiredFlags;
    public Integer needsReviewCount;
    public List<FeatureFlagDto> needsReviewFlags;
    public OffsetDateTime generatedAt;
}
