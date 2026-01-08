package villagecompute.storefront.platformops.api.types;

/**
 * Request payload for manual feature flag cache invalidation.
 *
 * <p>
 * Allows platform admins or automation to include a justification that will be stored in the audit log when forcing a
 * cache refresh.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Feature flag governance automation</li>
 * </ul>
 */
public class InvalidateFeatureFlagRequest {

    public String reason;
}
