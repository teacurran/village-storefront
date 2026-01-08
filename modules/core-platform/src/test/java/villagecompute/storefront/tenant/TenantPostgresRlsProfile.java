package villagecompute.storefront.tenant;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import villagecompute.storefront.testsupport.PostgresTenantTestResource;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Enables PostgreSQL-specific overrides (RLS + caching) for {@link TenantFilterTest}. The base %test profile already
 * uses Quarkus Dev Services for PostgreSQL, so this profile only toggles tenant safety features required by the suite.
 */
public class TenantPostgresRlsProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();
        config.put("tenant.rls.enabled", "true");
        config.put("tenant.cache.enabled", "true");
        config.put("quarkus.datasource.db-kind", "postgresql");
        return config;
    }

    @Override
    public List<TestResourceEntry> testResources() {
        return List.of(new TestResourceEntry(PostgresTenantTestResource.class));
    }
}
