package scripts;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Theme Token Exporter
 *
 * <p>
 * Exports theme tokens from database (or seed data) to JSON files for consumption by StorefrontResource. Generates:
 * <ul>
 * <li>theme-index.json - Map of subdomain to theme metadata</li>
 * <li>{subdomain}.json - Complete theme token object for each tenant</li>
 * </ul>
 *
 * <p>
 * Usage:
 *
 * <pre>
 * java scripts/ThemeExporter.java
 * java scripts/ThemeExporter.java --tenant-id=abc123
 * java scripts/ThemeExporter.java --all
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T6: Theme token export script hooking into database theme seed</li>
 * <li>UI/UX Architecture Section 1.1.4: Tenant Theming & Overrides</li>
 * </ul>
 */
public class ThemeExporter {

    private static final String OUTPUT_DIR = "modules/core-platform/src/main/resources/templates/storefront/_generated";
    private static final String THEME_INDEX_FILE = "theme-index.json";

    /**
     * Main entry point for theme export script. Accepts command-line arguments: --tenant-id=<uuid> export single
     * tenant --all export all tenants (default)
     *
     * @param args
     *            command-line arguments
     */
    public static void main(String[] args) {
        System.out.println("Village Storefront - Theme Token Exporter");
        System.out.println("==========================================");

        String tenantId = null;
        boolean exportAll = true;

        for (String arg : args) {
            if (arg.startsWith("--tenant-id=")) {
                tenantId = arg.substring("--tenant-id=".length());
                exportAll = false;
            } else if ("--all".equals(arg)) {
                exportAll = true;
            }
        }

        try {
            ThemeExporter exporter = new ThemeExporter();
            if (exportAll) {
                System.out.println("Exporting all tenant themes...");
                exporter.exportAllThemes();
            } else {
                System.out.println("Exporting theme for tenant: " + tenantId);
                exporter.exportSingleTheme(tenantId);
            }
            System.out.println("\nTheme export completed successfully!");
        } catch (Exception e) {
            System.err.println("ERROR: Theme export failed");
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Export all tenant themes. Currently generates seed data with default and demo themes. In production, this would
     * query the tenant_theme table.
     */
    public void exportAllThemes() throws IOException {
        // TODO: In production, query tenant_theme table from database
        // For now, generate seed themes for development

        Map<String, TenantTheme> themes = new HashMap<>();

        // Default theme (fallback)
        TenantTheme defaultTheme = createDefaultTheme();
        themes.put("default", defaultTheme);

        // Demo tenant theme
        TenantTheme demoTheme = createDemoTheme();
        themes.put("demo", demoTheme);

        // Example store theme
        TenantTheme exampleTheme = createExampleTheme();
        themes.put("example-store", exampleTheme);

        // Export theme index
        Map<String, ThemeIndexEntry> index = new LinkedHashMap<>();
        for (Map.Entry<String, TenantTheme> entry : themes.entrySet()) {
            String subdomain = entry.getKey();
            TenantTheme theme = entry.getValue();
            String jsonFile = subdomain + ".json";

            index.put(subdomain, new ThemeIndexEntry(subdomain, jsonFile));
            exportThemeJson(jsonFile, theme);

            System.out.println("Exported theme: " + subdomain + " -> " + jsonFile);
        }

        exportThemeIndex(index);
        System.out.println("Exported theme index: " + THEME_INDEX_FILE);
    }

    /**
     * Export single tenant theme by tenant ID.
     *
     * @param tenantId
     *            tenant UUID
     */
    public void exportSingleTheme(String tenantId) throws IOException {
        // TODO: In production, query tenant_theme table by tenant_id
        throw new UnsupportedOperationException("Single tenant export not yet implemented - use --all for now");
    }

    /**
     * Create default theme (platform fallback).
     */
    private TenantTheme createDefaultTheme() {
        TenantTheme theme = new TenantTheme();

        // Primary colors (blue scale)
        theme.primaryColors = new LinkedHashMap<>();
        theme.primaryColors.put("50", "#eff6ff");
        theme.primaryColors.put("100", "#dbeafe");
        theme.primaryColors.put("200", "#bfdbfe");
        theme.primaryColors.put("300", "#93c5fd");
        theme.primaryColors.put("400", "#60a5fa");
        theme.primaryColors.put("500", "#3b82f6");
        theme.primaryColors.put("600", "#2563eb");
        theme.primaryColors.put("700", "#1d4ed8");
        theme.primaryColors.put("800", "#1e40af");
        theme.primaryColors.put("900", "#1e3a8a");
        theme.primaryColors.put("950", "#172554");

        // Secondary colors (purple scale)
        theme.secondaryColors = new LinkedHashMap<>();
        theme.secondaryColors.put("50", "#f5f3ff");
        theme.secondaryColors.put("100", "#ede9fe");
        theme.secondaryColors.put("200", "#ddd6fe");
        theme.secondaryColors.put("300", "#c4b5fd");
        theme.secondaryColors.put("400", "#a78bfa");
        theme.secondaryColors.put("500", "#8b5cf6");
        theme.secondaryColors.put("600", "#7c3aed");
        theme.secondaryColors.put("700", "#6d28d9");
        theme.secondaryColors.put("800", "#5b21b6");
        theme.secondaryColors.put("900", "#4c1d95");
        theme.secondaryColors.put("950", "#2e1065");

        // Accent colors (orange scale)
        theme.accentColors = new LinkedHashMap<>();
        theme.accentColors.put("50", "#fff7ed");
        theme.accentColors.put("100", "#ffedd5");
        theme.accentColors.put("200", "#fed7aa");
        theme.accentColors.put("300", "#fdba74");
        theme.accentColors.put("400", "#fb923c");
        theme.accentColors.put("500", "#f97316");
        theme.accentColors.put("600", "#ea580c");
        theme.accentColors.put("700", "#c2410c");
        theme.accentColors.put("800", "#9a3412");
        theme.accentColors.put("900", "#7c2d12");
        theme.accentColors.put("950", "#431407");

        return theme;
    }

    /**
     * Create demo theme (teal/emerald palette).
     */
    private TenantTheme createDemoTheme() {
        TenantTheme theme = new TenantTheme();

        // Primary colors (teal scale)
        theme.primaryColors = new LinkedHashMap<>();
        theme.primaryColors.put("50", "#f0fdfa");
        theme.primaryColors.put("100", "#ccfbf1");
        theme.primaryColors.put("200", "#99f6e4");
        theme.primaryColors.put("300", "#5eead4");
        theme.primaryColors.put("400", "#2dd4bf");
        theme.primaryColors.put("500", "#14b8a6");
        theme.primaryColors.put("600", "#0d9488");
        theme.primaryColors.put("700", "#0f766e");
        theme.primaryColors.put("800", "#115e59");
        theme.primaryColors.put("900", "#134e4a");
        theme.primaryColors.put("950", "#042f2e");

        // Secondary colors (emerald scale)
        theme.secondaryColors = new LinkedHashMap<>();
        theme.secondaryColors.put("50", "#ecfdf5");
        theme.secondaryColors.put("100", "#d1fae5");
        theme.secondaryColors.put("200", "#a7f3d0");
        theme.secondaryColors.put("300", "#6ee7b7");
        theme.secondaryColors.put("400", "#34d399");
        theme.secondaryColors.put("500", "#10b981");
        theme.secondaryColors.put("600", "#059669");
        theme.secondaryColors.put("700", "#047857");
        theme.secondaryColors.put("800", "#065f46");
        theme.secondaryColors.put("900", "#064e3b");
        theme.secondaryColors.put("950", "#022c22");

        // Accent colors (amber scale)
        theme.accentColors = new LinkedHashMap<>();
        theme.accentColors.put("50", "#fffbeb");
        theme.accentColors.put("100", "#fef3c7");
        theme.accentColors.put("200", "#fde68a");
        theme.accentColors.put("300", "#fcd34d");
        theme.accentColors.put("400", "#fbbf24");
        theme.accentColors.put("500", "#f59e0b");
        theme.accentColors.put("600", "#d97706");
        theme.accentColors.put("700", "#b45309");
        theme.accentColors.put("800", "#92400e");
        theme.accentColors.put("900", "#78350f");
        theme.accentColors.put("950", "#451a03");

        return theme;
    }

    /**
     * Create example store theme (indigo/rose palette).
     */
    private TenantTheme createExampleTheme() {
        TenantTheme theme = new TenantTheme();

        // Primary colors (indigo scale)
        theme.primaryColors = new LinkedHashMap<>();
        theme.primaryColors.put("50", "#eef2ff");
        theme.primaryColors.put("100", "#e0e7ff");
        theme.primaryColors.put("200", "#c7d2fe");
        theme.primaryColors.put("300", "#a5b4fc");
        theme.primaryColors.put("400", "#818cf8");
        theme.primaryColors.put("500", "#6366f1");
        theme.primaryColors.put("600", "#4f46e5");
        theme.primaryColors.put("700", "#4338ca");
        theme.primaryColors.put("800", "#3730a3");
        theme.primaryColors.put("900", "#312e81");
        theme.primaryColors.put("950", "#1e1b4b");

        // Secondary colors (rose scale)
        theme.secondaryColors = new LinkedHashMap<>();
        theme.secondaryColors.put("50", "#fff1f2");
        theme.secondaryColors.put("100", "#ffe4e6");
        theme.secondaryColors.put("200", "#fecdd3");
        theme.secondaryColors.put("300", "#fda4af");
        theme.secondaryColors.put("400", "#fb7185");
        theme.secondaryColors.put("500", "#f43f5e");
        theme.secondaryColors.put("600", "#e11d48");
        theme.secondaryColors.put("700", "#be123c");
        theme.secondaryColors.put("800", "#9f1239");
        theme.secondaryColors.put("900", "#881337");
        theme.secondaryColors.put("950", "#4c0519");

        // Accent colors (cyan scale)
        theme.accentColors = new LinkedHashMap<>();
        theme.accentColors.put("50", "#ecfeff");
        theme.accentColors.put("100", "#cffafe");
        theme.accentColors.put("200", "#a5f3fc");
        theme.accentColors.put("300", "#67e8f9");
        theme.accentColors.put("400", "#22d3ee");
        theme.accentColors.put("500", "#06b6d4");
        theme.accentColors.put("600", "#0891b2");
        theme.accentColors.put("700", "#0e7490");
        theme.accentColors.put("800", "#155e75");
        theme.accentColors.put("900", "#164e63");
        theme.accentColors.put("950", "#083344");

        return theme;
    }

    /**
     * Export theme index to JSON file.
     */
    private void exportThemeIndex(Map<String, ThemeIndexEntry> index) throws IOException {
        Path outputPath = Paths.get(OUTPUT_DIR, THEME_INDEX_FILE);
        ensureDirectoryExists(outputPath.getParent());

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), index);
    }

    /**
     * Export individual theme to JSON file.
     */
    private void exportThemeJson(String filename, TenantTheme theme) throws IOException {
        Path outputPath = Paths.get(OUTPUT_DIR, filename);
        ensureDirectoryExists(outputPath.getParent());

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(outputPath.toFile(), theme);
    }

    /**
     * Ensure output directory exists.
     */
    private void ensureDirectoryExists(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            Files.createDirectories(directory);
        }
    }

    /**
     * Theme index entry.
     */
    public static class ThemeIndexEntry {
        public String subdomain;
        public String jsonFile;

        public ThemeIndexEntry() {
        }

        public ThemeIndexEntry(String subdomain, String jsonFile) {
            this.subdomain = subdomain;
            this.jsonFile = jsonFile;
        }
    }

    /**
     * Complete tenant theme structure.
     */
    public static class TenantTheme {
        public Map<String, String> primaryColors;
        public Map<String, String> secondaryColors;
        public Map<String, String> accentColors;

        // Typography tokens (optional, tenant-customizable in future)
        public String fontFamilyPrimary = "Inter, sans-serif";
        public String fontFamilySecondary = "Source Serif Pro, serif";
        public String fontFamilyMono = "JetBrains Mono, monospace";

        public TenantTheme() {
        }
    }
}
