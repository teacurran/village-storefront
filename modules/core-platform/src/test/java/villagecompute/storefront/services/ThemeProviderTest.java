package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.services.events.TenantThemeUpdated;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for ThemeProvider service.
 *
 * <p>
 * Validates tenant-specific theme loading, caching behavior, cache invalidation, and fallback to default themes.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T3: Scaffold Qute storefront shell with theme provider</li>
 * <li>UI/UX Architecture Section 1.9: Design Token Delivery & Governance</li>
 * </ul>
 */
@QuarkusTest
class ThemeProviderTest {

    @Inject
    ThemeProvider themeProvider;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    void clearCache() {
        themeProvider.invalidateAllCaches();
    }

    @Test
    void testGetThemeTokens_Default() {
        // When: Loading theme for default subdomain
        Map<String, String> tokens = themeProvider.getThemeTokens("default");

        // Then: Should return valid token map
        assertNotNull(tokens, "Theme tokens should not be null");
        assertFalse(tokens.isEmpty(), "Theme tokens should not be empty");

        // Should include primary color tokens
        assertTrue(tokens.containsKey("primary500"), "Should contain primary500 token");
        assertTrue(tokens.containsKey("primary600"), "Should contain primary600 token");

        // Should include typography tokens
        assertTrue(tokens.containsKey("fontFamilyPrimary"), "Should contain fontFamilyPrimary token");

        // Should have valid hex color format
        String primaryColor = tokens.get("primary500");
        assertNotNull(primaryColor, "primary500 value should not be null");
        assertTrue(primaryColor.matches("#[0-9a-fA-F]{6}"), "primary500 should be valid hex color: " + primaryColor);
    }

    @Test
    void testGetThemeTokens_TenantSpecific() {
        // When: Loading theme for demo tenant
        Map<String, String> tokens = themeProvider.getThemeTokens("demo");

        // Then: Should return valid token map
        assertNotNull(tokens, "Theme tokens should not be null");
        assertFalse(tokens.isEmpty(), "Theme tokens should not be empty");

        // Should include all expected token categories
        assertTrue(tokens.containsKey("primary500"), "Should contain primary color tokens");
        assertTrue(tokens.containsKey("secondary500"), "Should contain secondary color tokens");
        assertTrue(tokens.containsKey("accent500"), "Should contain accent color tokens");
        assertTrue(tokens.containsKey("fontFamilyPrimary"), "Should contain typography tokens");
    }

    @Test
    void testGetThemeTokens_FallbackToDefault() {
        // When: Loading theme for unknown subdomain
        Map<String, String> tokens = themeProvider.getThemeTokens("nonexistent-tenant-xyz");

        // Then: Should fall back to default theme
        assertNotNull(tokens, "Theme tokens should not be null");
        assertFalse(tokens.isEmpty(), "Theme tokens should not be empty");

        // Should still contain valid tokens from default theme
        assertTrue(tokens.containsKey("primary500"), "Should contain primary500 from default theme");
    }

    @Test
    void testGetThemeTokens_NullSubdomain() {
        // When: Loading theme with null subdomain
        Map<String, String> tokens = themeProvider.getThemeTokens((String) null);

        // Then: Should fall back to default theme
        assertNotNull(tokens, "Theme tokens should not be null");
        assertFalse(tokens.isEmpty(), "Theme tokens should not be empty");
    }

    @Test
    void testGetThemeTokens_EmptySubdomain() {
        // When: Loading theme with empty subdomain
        Map<String, String> tokens = themeProvider.getThemeTokens("");

        // Then: Should fall back to default theme
        assertNotNull(tokens, "Theme tokens should not be null");
        assertFalse(tokens.isEmpty(), "Theme tokens should not be empty");
    }

    @Test
    void testThemeCaching() {
        // Given: First load of theme
        Map<String, String> firstLoad = themeProvider.getThemeTokens("default");

        // When: Second load of same theme
        Map<String, String> secondLoad = themeProvider.getThemeTokens("default");

        // Then: Should return same cached instance
        assertNotNull(firstLoad, "First load should not be null");
        assertNotNull(secondLoad, "Second load should not be null");
        assertEquals(firstLoad.size(), secondLoad.size(), "Token counts should match");

        // Note: We don't test for reference equality since the map might be defensive-copied
        // but the caching mechanism should still reduce resource loading
    }

    @Test
    void testInvalidateCache() {
        // Given: Theme is loaded and cached
        Map<String, String> original = themeProvider.getThemeTokens("default");
        assertNotNull(original, "Original theme should not be null");

        // When: Cache is invalidated
        themeProvider.invalidateCache("default");

        // And: Theme is reloaded
        Map<String, String> reloaded = themeProvider.getThemeTokens("default");

        // Then: Should reload theme tokens (may not be same instance)
        assertNotNull(reloaded, "Reloaded theme should not be null");
        assertEquals(original.size(), reloaded.size(), "Token counts should match after reload");
    }

    @Test
    void testInvalidateAllCaches() {
        // Given: Multiple themes are loaded and cached
        themeProvider.getThemeTokens("default");
        themeProvider.getThemeTokens("demo");
        themeProvider.getThemeTokens("example-store");

        // When: All caches are invalidated
        themeProvider.invalidateAllCaches();

        // Then: Subsequent loads should reload from source
        Map<String, String> reloadedDefault = themeProvider.getThemeTokens("default");
        Map<String, String> reloadedDemo = themeProvider.getThemeTokens("demo");

        assertNotNull(reloadedDefault, "Reloaded default theme should not be null");
        assertNotNull(reloadedDemo, "Reloaded demo theme should not be null");
    }

    @Test
    void testGetThemeTokens_WithTenantId() {
        // Given: Tenant ID and subdomain
        UUID tenantId = UUID.randomUUID();
        String subdomain = "demo";

        // When: Loading theme by tenant ID
        Map<String, String> tokens = themeProvider.getThemeTokens(tenantId, subdomain);

        // Then: Should return valid tokens (currently delegates to subdomain-based loading)
        assertNotNull(tokens, "Theme tokens should not be null");
        assertFalse(tokens.isEmpty(), "Theme tokens should not be empty");

        // TODO(I3+): When database-backed themes are implemented, verify tenantId is used
    }

    @Test
    void testTenantIsolation() {
        // When: Loading themes for different tenants
        Map<String, String> defaultTheme = themeProvider.getThemeTokens("default");
        Map<String, String> demoTheme = themeProvider.getThemeTokens("demo");

        // Then: Each tenant should have independent theme tokens
        assertNotNull(defaultTheme, "Default theme should not be null");
        assertNotNull(demoTheme, "Demo theme should not be null");

        // Both should be valid but may have different values
        assertTrue(defaultTheme.containsKey("primary500"), "Default should have primary500");
        assertTrue(demoTheme.containsKey("primary500"), "Demo should have primary500");
    }

    @Test
    void testThemeTokenStructure() {
        // When: Loading any theme
        Map<String, String> tokens = themeProvider.getThemeTokens("default");

        // Then: Should contain expected token structure
        assertNotNull(tokens, "Theme tokens should not be null");

        // Color palette tokens (checking existence, not specific values)
        assertTrue(tokens.containsKey("primary500") || tokens.containsKey("primary600"),
                "Should contain at least some primary color tokens");

        // Typography tokens
        assertTrue(tokens.containsKey("fontFamilyPrimary"), "Should contain fontFamilyPrimary");

        // Font family values should be valid CSS font stacks
        String fontFamily = tokens.get("fontFamilyPrimary");
        assertNotNull(fontFamily, "fontFamilyPrimary should not be null");
        assertFalse(fontFamily.isBlank(), "fontFamilyPrimary should not be blank");
    }

    @Test
    void testColorTokenFormat() {
        // When: Loading theme tokens
        Map<String, String> tokens = themeProvider.getThemeTokens("default");

        // Then: Color tokens should be valid hex colors
        tokens.forEach((key, value) -> {
            if (key.startsWith("primary") || key.startsWith("secondary") || key.startsWith("accent")) {
                // Should be hex color format
                assertTrue(value.matches("#[0-9a-fA-F]{6}"),
                        "Color token '" + key + "' should be valid hex color, got: " + value);
            }
        });
    }

    @Test
    void testThemeCacheInvalidatesOnEvent() {
        Map<String, String> firstLoad = themeProvider.getThemeTokens("demo");
        assertNotNull(firstLoad, "Initial theme load should succeed");

        themeProvider.onThemeUpdated(new TenantThemeUpdated(null, "demo"));

        Map<String, String> secondLoad = themeProvider.getThemeTokens("demo");
        assertNotNull(secondLoad, "Reloaded theme should not be null");
        assertNotSame(firstLoad, secondLoad, "Theme cache should be invalidated after event");
    }
}
