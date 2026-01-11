package villagecompute.storefront.services;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.services.events.TenantThemeUpdated;

/**
 * Provides tenant-specific theme tokens for storefront rendering.
 *
 * <p>
 * Loads theme design tokens from JSON files (currently) or database (future), applies tenant-specific overrides, and
 * caches results for performance. Responds to cache invalidation events when themes are updated.
 *
 * <p>
 * Design tokens include:
 * <ul>
 * <li>Color palette (primary, secondary, accent shades)</li>
 * <li>Typography (font families)</li>
 * <li>Spacing and layout tokens</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T3: Scaffold Qute storefront shell with theme token loader</li>
 * <li>UI/UX Architecture Section 1.9: Design Token Delivery & Governance</li>
 * <li>Architecture: Multi-tenant theme injection via CSS variables</li>
 * </ul>
 */
@ApplicationScoped
public class ThemeProvider {

    private static final Logger LOG = Logger.getLogger(ThemeProvider.class);
    private static final String THEME_RESOURCE_PREFIX = "templates/storefront/_generated/";
    private static final String THEME_INDEX_RESOURCE = THEME_RESOURCE_PREFIX + "theme-index.json";
    private static final String DEFAULT_THEME_KEY = "default";

    private final Map<String, Map<String, String>> themeCache = new ConcurrentHashMap<>();

    @Inject
    ObjectMapper objectMapper;

    /**
     * Load theme tokens for the specified subdomain.
     *
     * <p>
     * Falls back to the default theme if tenant-specific theme is not found. Returns cached tokens if available,
     * otherwise loads from JSON files and caches the result.
     *
     * @param subdomain
     *            tenant subdomain
     * @return map of theme tokens (e.g., "primary500" -> "#3b82f6")
     */
    public Map<String, String> getThemeTokens(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            subdomain = DEFAULT_THEME_KEY;
        }

        // Check cache first
        Map<String, String> cached = themeCache.get(subdomain);
        if (cached != null) {
            LOG.debugf("Cache hit for theme: %s", subdomain);
            return cached;
        }

        // Load theme tokens
        Map<String, String> tokens = loadThemeTokens(subdomain);
        themeCache.put(subdomain, tokens);
        LOG.infof("Loaded and cached theme tokens for subdomain: %s (%d tokens)", subdomain, tokens.size());

        return tokens;
    }

    /**
     * Load theme tokens for the specified tenant ID. This variant supports future database-backed themes.
     *
     * @param tenantId
     *            tenant UUID
     * @param subdomain
     *            tenant subdomain (used as cache key and fallback)
     * @return map of theme tokens
     */
    public Map<String, String> getThemeTokens(UUID tenantId, String subdomain) {
        // TODO(I3+): Load from tenant_theme table using tenantId
        // For now, delegate to subdomain-based loading
        return getThemeTokens(subdomain);
    }

    /**
     * Invalidate cached theme tokens for a specific tenant. Call this when a theme is updated via admin UI.
     *
     * @param subdomain
     *            tenant subdomain
     */
    public void invalidateCache(String subdomain) {
        if (subdomain == null || subdomain.isBlank()) {
            subdomain = DEFAULT_THEME_KEY;
        }
        themeCache.remove(subdomain);
        LOG.infof("Invalidated theme cache for subdomain: %s", subdomain);
    }

    /**
     * Invalidate all cached theme tokens. Use after platform-level theme changes.
     */
    public void invalidateAllCaches() {
        int count = themeCache.size();
        themeCache.clear();
        LOG.infof("Invalidated all theme caches (%d entries cleared)", count);
    }

    /**
     * CDI event observer for theme update events. Automatically invalidates cache when themes are updated.
     *
     * <p>
     * Future implementation will trigger on TenantThemeUpdated event.
     *
     * @param event
     *            theme update event (placeholder for future implementation)
     */
    void onThemeUpdated(@Observes TenantThemeUpdated event) {
        if (event == null) {
            return;
        }

        if (event.invalidateAllTenants()) {
            LOG.info("TenantThemeUpdated event received for all tenants - invalidating entire theme cache");
            invalidateAllCaches();
            return;
        }

        String cacheKey = event.subdomain();
        if (cacheKey == null || cacheKey.isBlank()) {
            cacheKey = DEFAULT_THEME_KEY;
        }

        LOG.debugf("TenantThemeUpdated event received for subdomain %s - refreshing cache", cacheKey);
        invalidateCache(cacheKey);
    }

    /**
     * Load theme tokens from JSON files. Reads theme index to locate tenant-specific theme file, then parses color
     * palettes and typography tokens.
     *
     * @param subdomain
     *            tenant subdomain
     * @return map of theme tokens
     */
    private Map<String, String> loadThemeTokens(String subdomain) {
        Map<String, String> tokens = new HashMap<>();

        try {
            JsonNode indexNode = readJsonResource(THEME_INDEX_RESOURCE);
            if (indexNode == null || indexNode.size() == 0) {
                LOG.warn("Theme index not found or empty, using default fallback");
                return getDefaultTokens();
            }

            // Resolve theme entry (tenant-specific or default)
            JsonNode themeEntry = indexNode.path(subdomain);
            if (themeEntry.isMissingNode()) {
                LOG.debugf("No theme entry for subdomain '%s', falling back to default", subdomain);
                themeEntry = indexNode.path(DEFAULT_THEME_KEY);
            }
            if (themeEntry.isMissingNode()) {
                LOG.warn("Default theme entry not found in index, using first available");
                themeEntry = indexNode.elements().hasNext() ? indexNode.elements().next() : null;
            }
            if (themeEntry == null || themeEntry.isMissingNode()) {
                LOG.warn("No theme entries found in index, using default fallback");
                return getDefaultTokens();
            }

            // Load theme JSON file
            String jsonFile = themeEntry.path("jsonFile").asText();
            if (jsonFile == null || jsonFile.isBlank()) {
                LOG.warnf("Theme entry for '%s' missing jsonFile property, using default fallback", subdomain);
                return getDefaultTokens();
            }

            JsonNode themeNode = readJsonResource(THEME_RESOURCE_PREFIX + jsonFile);
            if (themeNode == null) {
                LOG.warnf("Theme file '%s' not found, using default fallback", jsonFile);
                return getDefaultTokens();
            }

            // Parse color palettes
            populateColorTokens(tokens, "primary", themeNode.path("primaryColors"));
            populateColorTokens(tokens, "secondary", themeNode.path("secondaryColors"));
            populateColorTokens(tokens, "accent", themeNode.path("accentColors"));

            // Parse typography tokens
            tokens.putIfAbsent("fontFamilyPrimary", themeNode.path("fontFamilyPrimary").asText("Inter, sans-serif"));
            tokens.putIfAbsent("fontFamilySecondary",
                    themeNode.path("fontFamilySecondary").asText("Source Serif Pro, serif"));
            tokens.putIfAbsent("fontFamilyMono", themeNode.path("fontFamilyMono").asText("JetBrains Mono, monospace"));

        } catch (IOException e) {
            LOG.error("Failed to load theme tokens from JSON files", e);
            return getDefaultTokens();
        }

        return tokens;
    }

    /**
     * Read JSON resource from classpath.
     *
     * @param resourcePath
     *            resource path relative to classpath root
     * @return parsed JSON node, or null if resource not found
     * @throws IOException
     *             if JSON parsing fails
     */
    private JsonNode readJsonResource(String resourcePath) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = classLoader.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                LOG.debugf("Theme resource not found: %s", resourcePath);
                return null;
            }
            return objectMapper.readTree(stream);
        }
    }

    /**
     * Populate token map with color palette values.
     *
     * @param tokens
     *            target token map
     * @param prefix
     *            token prefix (e.g., "primary", "secondary")
     * @param paletteNode
     *            JSON node containing color shades
     */
    private void populateColorTokens(Map<String, String> tokens, String prefix, JsonNode paletteNode) {
        if (paletteNode == null || paletteNode.isMissingNode()) {
            return;
        }

        paletteNode.fieldNames().forEachRemaining(key -> {
            String value = paletteNode.path(key).asText();
            if (value != null && !value.isBlank()) {
                tokens.put(prefix + key, value);
            }
        });
    }

    /**
     * Get default theme tokens as fallback.
     *
     * @return minimal default token map
     */
    private Map<String, String> getDefaultTokens() {
        Map<String, String> defaults = new HashMap<>();
        // Provide minimal defaults matching Tailwind's blue palette
        defaults.put("primary500", "#3b82f6");
        defaults.put("primary600", "#2563eb");
        defaults.put("primary700", "#1d4ed8");
        defaults.put("secondary500", "#8b5cf6");
        defaults.put("secondary600", "#7c3aed");
        defaults.put("accent500", "#f97316");
        defaults.put("accent600", "#ea580c");
        defaults.put("fontFamilyPrimary", "Inter, sans-serif");
        defaults.put("fontFamilySecondary", "Source Serif Pro, serif");
        defaults.put("fontFamilyMono", "JetBrains Mono, monospace");
        return defaults;
    }
}
