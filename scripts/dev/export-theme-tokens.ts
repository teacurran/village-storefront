#!/usr/bin/env ts-node
/**
 * Theme Token Export Script
 *
 * Exports tenant theme tokens from the database to CSS/JSON formats for use with Tailwind and Qute templates.
 * This script is designed to be run during development or as part of the build process to generate
 * tenant-specific theme files.
 *
 * Usage:
 *   # Export all tenant themes
 *   ./scripts/dev/export-theme-tokens.ts
 *
 *   # Export specific tenant theme
 *   ./scripts/dev/export-theme-tokens.ts --tenant-id=<uuid>
 *
 *   # Export to custom output directory
 *   ./scripts/dev/export-theme-tokens.ts --output-dir=./build/themes
 *
 * References:
 * - Task I2.T6: Storefront Qute partials + theme token export
 * - UI/UX Architecture Section 1.1.4: Tenant Theming & Overrides
 * - UI/UX Architecture Section 1.9: Design Token Delivery & Governance
 */

import { Client } from 'pg';
import * as fs from 'fs';
import * as path from 'path';

// ========================================
// Configuration
// ========================================

interface Config {
    databaseUrl: string;
    outputDir: string;
    tenantId?: string;
}

interface TenantTheme {
    tenantId: string;
    tenantSubdomain: string;
    primaryColors: Record<string, string>;
    secondaryColors: Record<string, string>;
    customCss?: string;
}

interface ColorScale {
    50: string;
    100: string;
    200: string;
    300: string;
    400: string;
    500: string;
    600: string;
    700: string;
    800: string;
    900: string;
    950: string;
}

// ========================================
// Argument Parsing
// ========================================

function parseArgs(): Config {
    const args = process.argv.slice(2);
    const config: Config = {
        databaseUrl: process.env.DATABASE_URL || 'postgresql://storefront:storefront@localhost:5432/storefront',
        outputDir: './modules/core-platform/src/main/resources/templates/storefront/_generated',
        tenantId: process.env.TENANT_ID || process.env.TENANT || undefined,
    };

    args.forEach((arg) => {
        if (arg.startsWith('--tenant-id=')) {
            config.tenantId = arg.split('=')[1];
        } else if (arg.startsWith('--tenant=')) {
            config.tenantId = arg.split('=')[1];
        } else if (arg.startsWith('--output-dir=')) {
            config.outputDir = arg.split('=')[1];
        } else if (arg.startsWith('--database-url=')) {
            config.databaseUrl = arg.split('=')[1];
        }
    });

    return config;
}

// ========================================
// Database Operations
// ========================================

/**
 * Fetch theme data from the database.
 * Note: This assumes a tenant_theme table will exist in future migrations.
 */
async function fetchTenantThemes(client: Client, tenantId?: string): Promise<TenantTheme[]> {
    console.log('Fetching tenant themes from database...');

    // TODO: Once tenant_theme table is created in migrations, use this query:
    // const query = tenantId
    //     ? 'SELECT * FROM tenant_theme WHERE tenant_id = $1'
    //     : 'SELECT * FROM tenant_theme';
    // const result = tenantId ? await client.query(query, [tenantId]) : await client.query(query);

    // For now, return mock data for development
    console.warn('⚠️  tenant_theme table not yet implemented - using mock data');

    const mockThemes: TenantTheme[] = [
        {
            tenantId: '00000000-0000-0000-0000-000000000001',
            tenantSubdomain: 'demo',
            primaryColors: generateColorScale('#3b82f6'), // Blue
            secondaryColors: generateColorScale('#8b5cf6'), // Purple
        },
    ];

    return tenantId ? mockThemes.filter((t) => t.tenantId === tenantId) : mockThemes;
}

/**
 * Generate a full color scale from a base color.
 * This is a simplified implementation; production code would use proper color manipulation libraries.
 */
function generateColorScale(baseColor: string): Record<string, string> {
    // For now, return predefined scales based on base color
    // In production, use a library like chroma.js or tinycolor2 to generate proper scales

    const scales: Record<string, ColorScale> = {
        '#3b82f6': {
            // Blue
            50: '#eff6ff',
            100: '#dbeafe',
            200: '#bfdbfe',
            300: '#93c5fd',
            400: '#60a5fa',
            500: '#3b82f6',
            600: '#2563eb',
            700: '#1d4ed8',
            800: '#1e40af',
            900: '#1e3a8a',
            950: '#172554',
        },
        '#8b5cf6': {
            // Purple
            50: '#f5f3ff',
            100: '#ede9fe',
            200: '#ddd6fe',
            300: '#c4b5fd',
            400: '#a78bfa',
            500: '#8b5cf6',
            600: '#7c3aed',
            700: '#6d28d9',
            800: '#5b21b6',
            900: '#4c1d95',
            950: '#2e1065',
        },
    };

    return scales[baseColor] || scales['#3b82f6'];
}

// ========================================
// Export Functions
// ========================================

/**
 * Export theme as CSS custom properties for runtime theming.
 */
function exportAsCSS(theme: TenantTheme, outputDir: string): string {
    const cssFilePath = path.join(outputDir, `_theme-${theme.tenantSubdomain}.css`);

    const css = `
/**
 * Tenant Theme: ${theme.tenantSubdomain}
 * Auto-generated by export-theme-tokens.ts
 * DO NOT EDIT MANUALLY
 */

:root {
    /* Primary Colors */
    --color-primary-50: ${theme.primaryColors['50']};
    --color-primary-100: ${theme.primaryColors['100']};
    --color-primary-200: ${theme.primaryColors['200']};
    --color-primary-300: ${theme.primaryColors['300']};
    --color-primary-400: ${theme.primaryColors['400']};
    --color-primary-500: ${theme.primaryColors['500']};
    --color-primary-600: ${theme.primaryColors['600']};
    --color-primary-700: ${theme.primaryColors['700']};
    --color-primary-800: ${theme.primaryColors['800']};
    --color-primary-900: ${theme.primaryColors['900']};
    --color-primary-950: ${theme.primaryColors['950']};

    /* Secondary Colors */
    --color-secondary-50: ${theme.secondaryColors['50']};
    --color-secondary-100: ${theme.secondaryColors['100']};
    --color-secondary-200: ${theme.secondaryColors['200']};
    --color-secondary-300: ${theme.secondaryColors['300']};
    --color-secondary-400: ${theme.secondaryColors['400']};
    --color-secondary-500: ${theme.secondaryColors['500']};
    --color-secondary-600: ${theme.secondaryColors['600']};
    --color-secondary-700: ${theme.secondaryColors['700']};
    --color-secondary-800: ${theme.secondaryColors['800']};
    --color-secondary-900: ${theme.secondaryColors['900']};
    --color-secondary-950: ${theme.secondaryColors['950']};
}

${theme.customCss || ''}
`.trim();

    fs.writeFileSync(cssFilePath, css);
    console.log(`✓ Exported CSS: ${cssFilePath}`);

    return cssFilePath;
}

/**
 * Export theme as JSON for programmatic access.
 */
function exportAsJSON(theme: TenantTheme, outputDir: string): string {
    const jsonFilePath = path.join(outputDir, `theme-${theme.tenantSubdomain}.json`);

    const json = JSON.stringify(
        {
            tenantId: theme.tenantId,
            tenantSubdomain: theme.tenantSubdomain,
            primaryColors: theme.primaryColors,
            secondaryColors: theme.secondaryColors,
        },
        null,
        2
    );

    fs.writeFileSync(jsonFilePath, json);
    console.log(`✓ Exported JSON: ${jsonFilePath}`);

    return jsonFilePath;
}

/**
 * Export theme index file that maps subdomains to theme files.
 */
function exportThemeIndex(themes: TenantTheme[], outputDir: string): string {
    const indexPath = path.join(outputDir, 'theme-index.json');

    const index = themes.reduce((acc, theme) => {
        acc[theme.tenantSubdomain] = {
            cssFile: `_theme-${theme.tenantSubdomain}.css`,
            jsonFile: `theme-${theme.tenantSubdomain}.json`,
            tenantId: theme.tenantId,
        };
        return acc;
    }, {} as Record<string, any>);

    fs.writeFileSync(indexPath, JSON.stringify(index, null, 2));
    console.log(`✓ Exported theme index: ${indexPath}`);

    return indexPath;
}

// ========================================
// Main
// ========================================

async function main() {
    console.log('🎨 Theme Token Export Script');
    console.log('================================\n');

    const config = parseArgs();

    // Ensure output directory exists
    if (!fs.existsSync(config.outputDir)) {
        fs.mkdirSync(config.outputDir, { recursive: true });
        console.log(`Created output directory: ${config.outputDir}\n`);
    }

    // Connect to database
    const client = new Client({ connectionString: config.databaseUrl });

    try {
        await client.connect();
        console.log('✓ Connected to database\n');

        // Fetch themes
        const themes = await fetchTenantThemes(client, config.tenantId);
        console.log(`Found ${themes.length} tenant theme(s)\n`);

        if (themes.length === 0) {
            console.warn('⚠️  No themes found. Exiting.');
            return;
        }

        // Export each theme
        for (const theme of themes) {
            console.log(`Exporting theme for: ${theme.tenantSubdomain}`);
            exportAsCSS(theme, config.outputDir);
            exportAsJSON(theme, config.outputDir);
            console.log();
        }

        // Export theme index
        exportThemeIndex(themes, config.outputDir);

        console.log('\n✨ Theme export complete!');
        console.log('\nNext steps:');
        console.log('1. Review generated files in', config.outputDir);
        console.log('2. Include tenant-specific CSS in StorefrontResource.buildThemeTokens()');
        console.log('3. Update Tailwind build to reference exported tokens if needed\n');
    } catch (error) {
        console.error('❌ Error exporting themes:', error);
        process.exit(1);
    } finally {
        await client.end();
    }
}

// Run if executed directly
if (require.main === module) {
    main().catch((error) => {
        console.error('Fatal error:', error);
        process.exit(1);
    });
}

export { fetchTenantThemes, exportAsCSS, exportAsJSON, exportThemeIndex };
