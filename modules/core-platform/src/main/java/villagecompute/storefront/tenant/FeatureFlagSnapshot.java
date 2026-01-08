package villagecompute.storefront.tenant;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of feature flags for a tenant. Acts as a placeholder until feature toggle hydration populates the
 * actual toggle states.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001 Tenancy Strategy (Section 3: Feature flag governance)</li>
 * <li>Task I1.T5 Acceptance Criteria: Tenant filter populates feature flag placeholder</li>
 * </ul>
 */
public record FeatureFlagSnapshot(UUID tenantId, Map<String, Boolean> flags, OffsetDateTime fetchedAt,
        boolean hydrated) {

    public FeatureFlagSnapshot {
        Objects.requireNonNull(tenantId, "tenantId cannot be null");
        Map<String, Boolean> safeFlags = flags == null ? Collections.emptyMap() : new HashMap<>(flags);
        flags = Collections.unmodifiableMap(safeFlags);
        fetchedAt = fetchedAt != null ? fetchedAt : OffsetDateTime.now();
    }

    /**
     * Create placeholder snapshot for newly resolved tenant. Downstream services replace this snapshot after loading
     * real toggle data.
     *
     * @param tenantId
     *            tenant identifier
     * @return placeholder snapshot
     */
    public static FeatureFlagSnapshot placeholder(UUID tenantId) {
        return new FeatureFlagSnapshot(tenantId, Collections.emptyMap(), OffsetDateTime.now(), false);
    }

    /**
     * Convenience helper to check if flag is enabled within this snapshot.
     *
     * @param flagKey
     *            key to check
     * @return true if enabled, false otherwise
     */
    public boolean isEnabled(String flagKey) {
        Objects.requireNonNull(flagKey, "flagKey cannot be null");
        return flags.getOrDefault(flagKey, false);
    }
}
