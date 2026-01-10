package villagecompute.storefront.notifications;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Unit tests for {@link DomainFilterService}.
 *
 * <p>
 * Tests cover domain filtering behavior in different environments (dev, staging, production).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T5: Domain filtering for staging environment email safety</li>
 * <li>Acceptance Criteria: Non-prod filter prevents sending to real domains</li>
 * </ul>
 */
@QuarkusTest
class DomainFilterServiceTest {

    @Inject
    DomainFilterService domainFilterService;

    @Test
    void testFilterDisabled_AllowsAllDomains() {
        // In dev/test environments, filtering is disabled
        // All domains should be allowed
        assertTrue(domainFilterService.isAllowed("user@gmail.com"));
        assertTrue(domainFilterService.isAllowed("customer@yahoo.com"));
        assertTrue(domainFilterService.isAllowed("test@example.com"));
        assertTrue(domainFilterService.isAllowed("admin@villagecompute.com"));
    }

    @Test
    void testInvalidEmail_ReturnsFalse() {
        assertFalse(domainFilterService.isAllowed(null));
        assertFalse(domainFilterService.isAllowed(""));
        assertFalse(domainFilterService.isAllowed("   "));
        assertFalse(domainFilterService.isAllowed("invalid-email"));
        assertFalse(domainFilterService.isAllowed("no-at-sign.com"));
    }

    /**
     * Test profile that simulates staging environment with domain filtering enabled.
     */
    public static class StagingProfile implements io.quarkus.test.junit.QuarkusTestProfile {

        @Override
        public java.util.Map<String, String> getConfigOverrides() {
            return java.util.Map.of("notifications.domain-filter.enabled", "true",
                    "notifications.domain-filter.allowed", "villagecompute.com,example.com");
        }
    }

    /**
     * Staging environment tests with domain filtering enabled.
     */
    @QuarkusTest
    @TestProfile(StagingProfile.class)
    static class StagingDomainFilterTest {

        @Inject
        DomainFilterService domainFilterService;

        @Test
        void testFilterEnabled_AllowsWhitelistedDomains() {
            // Whitelisted domains should be allowed
            assertTrue(domainFilterService.isAllowed("user@villagecompute.com"));
            assertTrue(domainFilterService.isAllowed("admin@villagecompute.com"));
            assertTrue(domainFilterService.isAllowed("test@example.com"));
            assertTrue(domainFilterService.isAllowed("customer@example.com"));
        }

        @Test
        void testFilterEnabled_BlocksNonWhitelistedDomains() {
            // Non-whitelisted domains should be blocked
            assertFalse(domainFilterService.isAllowed("user@gmail.com"));
            assertFalse(domainFilterService.isAllowed("customer@yahoo.com"));
            assertFalse(domainFilterService.isAllowed("test@hotmail.com"));
            assertFalse(domainFilterService.isAllowed("admin@aol.com"));
            assertFalse(domainFilterService.isAllowed("user@realcustomer.com"));
        }

        @Test
        void testFilterEnabled_CaseInsensitive() {
            // Domain matching should be case-insensitive
            assertTrue(domainFilterService.isAllowed("user@VILLAGECOMPUTE.COM"));
            assertTrue(domainFilterService.isAllowed("user@VillageCompute.com"));
            assertTrue(domainFilterService.isAllowed("user@EXAMPLE.COM"));
            assertFalse(domainFilterService.isAllowed("user@GMAIL.COM"));
        }

        @Test
        void testFilterEnabled_InvalidEmail_ReturnsFalse() {
            assertFalse(domainFilterService.isAllowed(null));
            assertFalse(domainFilterService.isAllowed(""));
            assertFalse(domainFilterService.isAllowed("invalid-email"));
        }

        @Test
        void testIsFilterEnabled_ReturnsTrue() {
            assertTrue(domainFilterService.isFilterEnabled());
        }
    }
}
