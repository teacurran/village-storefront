# Storefront Theming & Customization Guide

This guide explains how to customize the Village Storefront appearance using design tokens and Tailwind CSS.

## Overview

The storefront uses a multi-tenant theming system that allows each tenant (store) to customize their brand colors while maintaining consistent semantic tokens for accessibility. The theming system consists of:

- **Base Design Tokens**: Platform-wide defaults defined in `tailwind.config.js`
- **Tenant Overrides**: Per-tenant color customizations stored in the database
- **CSS Custom Properties**: Runtime theming applied via CSS variables
- **Message Bundles**: Localized text content (en/es)

## Architecture

### Component Stack

```
┌─────────────────────────────────────┐
│     Qute Templates (.html)          │
│  - Base layout with grid system     │
│  - Reusable components               │
│  - Message bundle integration        │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│   CSS Custom Properties (:root)     │
│  - Tenant-specific color tokens     │
│  - Generated server-side per tenant │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│     Tailwind CSS Utilities          │
│  - Responsive grid system           │
│  - Pre-compiled utility classes     │
└──────────────┬──────────────────────┘
               │
┌──────────────▼──────────────────────┐
│        PrimeUI Components           │
│  - Enhanced form controls           │
│  - Progressive enhancement          │
└─────────────────────────────────────┘
```

## Design Token System

### Token Categories

**Primary Colors** (Tenant-customizable)
- Used for CTAs, links, brand elements
- Defined via `--color-primary-{50-950}` CSS variables
- Falls back to blue palette if not customized

**Secondary Colors** (Tenant-customizable)
- Used for accents, highlights
- Defined via `--color-secondary-{50-950}`
- Falls back to purple palette

**Semantic Colors** (Platform-controlled)
- Success (green), Warning (amber), Error (red)
- NOT customizable per tenant to ensure accessibility
- Contrast ratios meet WCAG 2.1 AA standards

**Neutral Colors** (Platform-controlled)
- Grays used for text, borders, backgrounds
- Ensures consistent readability

### Tailwind Configuration

The `tailwind.config.js` file defines the token mappings:

```javascript
colors: {
  primary: {
    500: 'var(--color-primary-500, #3b82f6)', // CSS var with fallback
    600: 'var(--color-primary-600, #2563eb)',
    // ... other shades
  }
}
```

This approach allows:
- **Build-time compilation**: Tailwind generates classes like `bg-primary-500`
- **Runtime customization**: CSS variables can change per tenant without rebuilding
- **Graceful fallbacks**: Default colors apply if tenant hasn't customized

## Customizing Tenant Themes

### Current Implementation (v1)

In the current iteration, tenant theme tokens are stubbed in `StorefrontResource.buildThemeTokens()`:

```java
private Map<String, String> buildThemeTokens(TenantInfo tenantInfo) {
    // TODO: Load from tenant_theme table
    return new HashMap<>(); // Uses defaults
}
```

### Future Implementation (Iteration I3+)

Tenant themes will be stored in the `tenant_theme` table:

```sql
CREATE TABLE tenant_theme (
    tenant_id UUID REFERENCES tenants(id),
    version INT NOT NULL,
    primary_50 VARCHAR(7),
    primary_500 VARCHAR(7),
    primary_600 VARCHAR(7),
    -- ... other color tokens
    created_at TIMESTAMPTZ DEFAULT NOW()
);
```

The resource will then load and apply these tokens:

```java
Map<String, String> themeTokens = themeService.loadTokens(tenantId);
// Returns: {"primary500": "#ff6b6b", "primary600": "#ee5a52", ...}
```

### Setting Custom Colors

When the admin UI is available (Iteration I3), merchants will:

1. Navigate to **Settings > Branding**
2. Use color pickers to customize primary/secondary colors
3. Preview changes in real-time against storefront mockups
4. See accessibility warnings if contrast ratios fail
5. Publish approved theme versions

Platform admins can lock themes for compliance-critical tenants (franchises).

## CSS Generation Pipeline

### Build Process

```bash
# Install Tailwind (one-time setup)
npm install -D tailwindcss

# Generate CSS during build
npx tailwindcss -i src/main/resources/css/input.css \
                -o src/main/resources/META-INF/resources/static/css/tailwind.css \
                --minify
```

### Development Mode

For hot-reloading during development:

```bash
# Watch mode (run in separate terminal)
npx tailwindcss -i src/main/resources/css/input.css \
                -o src/main/resources/META-INF/resources/static/css/tailwind.css \
                --watch

# Start Quarkus dev mode
./mvnw quarkus:dev
```

Changes to templates will trigger Qute recompilation automatically.

### Production Build

The Maven build can be configured to run Tailwind via `frontend-maven-plugin` (future enhancement):

```xml
<plugin>
    <groupId>com.github.eirslett</groupId>
    <artifactId>frontend-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>tailwind-build</id>
            <goals>
                <goal>npx</goal>
            </goals>
            <configuration>
                <arguments>tailwindcss -i ... -o ... --minify</arguments>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Template Customization

### Base Layout

The base layout (`templates/base.html`) provides:
- HTML structure with semantic elements
- Tenant-specific `<style>` block for CSS variables
- Meta tags for SEO and responsive design
- Skip-to-content link for accessibility
- Header/footer includes

### Component Library

Reusable components in `components/`:

**Header** (`components/header.html`)
- Logo/brand display
- Navigation from root categories
- Cart/account/search icons
- Mobile menu toggle

**Footer** (`components/footer.html`)
- Multi-column layout
- Category links
- Customer service links
- Copyright with dynamic year

**Hero** (`components/hero.html`)
- Full-width banner with gradient background
- Title, subtitle, CTA buttons
- Optional image support
- Decorative background elements

**ProductCard** (`components/product-card.html`)
- Product image with hover effects
- Price stack (sale/regular pricing)
- Quick-add button for simple products
- Sale/New badges
- Star ratings (when available)

### Creating New Templates

1. Create template in `src/main/resources/templates/StorefrontResource/`:

```html
{#include base.html}

{#head}
<title>Custom Page - {tenantName}</title>
{/head}

{#content}
<div class="max-w-8xl mx-auto px-4 py-12">
    <h1 class="text-4xl font-bold mb-6">{pageTitle}</h1>
    <p>{msg:custom_message}</p>
</div>
{/content}

{/include}
```

2. Add resource method in `StorefrontResource.java`:

```java
@CheckedTemplate
public static class Templates {
    public static native TemplateInstance customPage();
}

@GET
@Path("/custom")
public TemplateInstance customPage() {
    return Templates.customPage()
        .data("tenantName", getTenantName())
        .data("pageTitle", "Custom Page");
}
```

## Localization (i18n)

### Message Bundles

Messages are defined in:
- `src/main/resources/messages/messages.properties` (English)
- `src/main/resources/messages/messages_es.properties` (Spanish)

### Usage in Templates

Reference messages with the `{msg:key}` syntax:

```html
<button>{msg:add_to_cart}</button>
<p>{msg:showing_results}</p>
```

Qute will resolve based on the request locale (determined by `Accept-Language` header or tenant default).

### Adding New Messages

1. Add key to both `messages.properties` and `messages_es.properties`:

```properties
# messages.properties
product_unavailable=This product is currently unavailable

# messages_es.properties
product_unavailable=Este producto no está disponible actualmente
```

2. Use in templates:

```html
{#if !product.inStock}
<p class="text-error-600">{msg:product_unavailable}</p>
{/if}
```

### Parameterized Messages

For messages with placeholders:

```properties
# messages.properties
showing_results=Showing {count} results

# messages_es.properties
showing_results=Mostrando {count} resultados
```

Usage (requires template data):

```html
<p>{msg:showing_results}</p>
```

Pass `count` as template data:

```java
.data("count", products.size())
```

## Testing Theme Changes

### Running Tests

```bash
# Run all storefront rendering tests
./mvnw test -Dtest=StorefrontRenderingTest

# Run specific test
./mvnw test -Dtest=StorefrontRenderingTest#testTenantThemeCssVariables
```

### Visual Regression Testing

For visual diff testing (future enhancement):

```bash
# Take baseline screenshots
npm run test:visual:baseline

# Compare after changes
npm run test:visual:compare

# Uses Percy or similar tool to detect CSS regressions
```

## Accessibility Guidelines

### WCAG 2.1 AA Compliance

- **Color Contrast**: 4.5:1 for normal text, 3:1 for large text
- **Focus Indicators**: Visible focus rings on all interactive elements
- **Semantic HTML**: Proper heading hierarchy, landmark regions
- **ARIA Labels**: For icons and dynamic content
- **Keyboard Navigation**: Tab order follows visual layout

### Theme Validation

When customizing colors, ensure:
- Primary text on primary background meets 4.5:1 ratio
- CTA buttons have sufficient contrast
- Error/success messages are distinguishable by more than color alone

The admin UI (future) will show contrast warnings before publishing themes.

## Performance Considerations

### CSS Optimization

- **PurgeCSS**: Tailwind automatically removes unused classes in production
- **Minification**: CSS is minified during build
- **Cache Headers**: Static assets served with long-lived cache headers
- **Critical CSS**: Inline critical styles in `<head>` for LCP optimization

### Template Caching

Qute templates are compiled once and cached. Changes during development trigger automatic recompilation via Quarkus dev mode.

### Metrics

Server-side timing is logged for template rendering:

```
Homepage rendered in 45 ms - tenantId=abc123
```

Future: Micrometer metrics for LCP, FCP tracking.

## Troubleshooting

### Styles Not Applying

1. **Check CSS is linked**: Verify `<link rel="stylesheet" href="/static/css/tailwind.css">`
2. **Rebuild CSS**: Run `npx tailwindcss ...` to regenerate
3. **Clear browser cache**: Hard refresh (Cmd+Shift+R / Ctrl+Shift+F5)
4. **Verify Tailwind config**: Ensure `content` paths match template locations

### Messages Not Resolving

1. **Check bundle syntax**: Use `{msg:key}` not `{msg.key}`
2. **Verify key exists**: Search both `.properties` files
3. **Check locale**: Default is `en`, ensure fallback works

### Tenant Theme Not Loading

1. **Verify tenant resolution**: Check logs for "Rendering homepage - tenantId=..."
2. **Check CSS variables**: Inspect `:root` in browser DevTools
3. **Stub vs. DB**: Current version uses stubs, theme table not yet implemented

## References

- **Task**: I2.T2 - Build storefront base
- **Architecture**: `docs/architecture_overview.md`
- **UI/UX Spec**: `docs/diagrams/component_overview.puml`
- **Tailwind Docs**: https://tailwindcss.com/docs
- **Qute Guide**: https://quarkus.io/guides/qute
- **PrimeUI**: https://primeui.org

---

**Last Updated**: 2026-01-03 (Iteration I2)
**Next Steps**: Iteration I3 will add tenant_theme table, admin UI for customization, and visual preview mode.
