package villagecompute.storefront.tenant;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Enables PostgreSQL-specific overrides (RLS + caching) for {@link TenantFilterTest}. Uses Quarkus Dev Services for
 * automatic PostgreSQL provisioning.
 */
public class TenantPostgresRlsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();

        // Enable tenant safety features
        config.put("tenant.rls.enabled", "true");
        config.put("tenant.cache.enabled", "true");

        return config;
    }
}
