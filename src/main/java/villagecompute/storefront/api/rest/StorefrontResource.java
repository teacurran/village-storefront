package villagecompute.storefront.api.rest;

import java.time.Year;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;
import villagecompute.storefront.util.LocalizationService;

import io.micrometer.core.annotation.Timed;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;

/**
 * REST resource for server-side rendered storefront pages using Qute templates.
 *
 * <p>
 * Provides public-facing storefront routes that render HTML via Qute templates. Integrates with CatalogService for data
 * and applies tenant-specific theming through template variables.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Build storefront base with Qute layout and components</li>
 * <li>UI/UX Architecture Section 3.1.1: Storefront Routes</li>
 * <li>UI/UX Architecture Section 1.1.4: Tenant Theming & Overrides</li>
 * </ul>
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
public class StorefrontResource {

    private static final Logger LOG = Logger.getLogger(StorefrontResource.class);

    @Inject
    CatalogService catalogService;

    @Inject
    LocalizationService localizationService;

    /**
     * CheckedTemplate for type-safe Qute template references. Templates are located in src/main/resources/templates/
     */
    @CheckedTemplate(
            requireTypeSafeExpressions = false)
    public static class Templates {
        public static native TemplateInstance index();
    }

    /**
     * Homepage route - renders hero, categories, and featured products.
     *
     * @return rendered HTML
     */
    @GET
    @Timed(
            value = "storefront.homepage.render",
            description = "Homepage render time")
    public String index() {
        long startTime = System.currentTimeMillis();

        TenantInfo tenantInfo = TenantContext.getCurrentTenant();
        LOG.infof("Rendering homepage - tenantId=%s, domain=%s", tenantInfo.tenantId(), tenantInfo.subdomain());

        // Fetch root categories for navigation
        List<Category> rootCategories = catalogService.getRootCategories();
        LOG.debugf("Loaded %d root categories", rootCategories.size());
        List<Map<String, Object>> rootCategoryDisplay = mapCategoriesToDisplayData(rootCategories);

        // Fetch featured products (first page for now)
        List<Product> featuredProducts = catalogService.listActiveProducts(0, 8);
        LOG.debugf("Loaded %d featured products", featuredProducts.size());

        // Build hero data (stubbed for now - will come from CMS later)
        Map<String, Object> heroData = buildHeroData(tenantInfo);

        // Build theme tokens (stubbed for now - will come from tenant_theme table later)
        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);

        String locale = "en"; // TODO: derive from tenant or Accept-Language header
        Map<String, String> messages = localizationService.loadMessages(locale);

        long renderTime = System.currentTimeMillis() - startTime;
        LOG.infof("Homepage rendered in %d ms - tenantId=%s", renderTime, tenantInfo.tenantId());

        // Return template instance with data
        return Templates.index().data("tenantName", tenantInfo.name()).data("tenantSubdomain", tenantInfo.subdomain())
                .data("tenantLogo", null) // TODO: Load from
                                          // tenant config
                .data("tenantTagline", null) // TODO: Load from tenant config
                .data("seoTitle", null).data("seoDescription", null).data("canonicalUrl", null).data("msg", messages)
                .data("rootCategories", rootCategoryDisplay).data("collections", List.of())
                .data("featuredProducts", mapProductsToDisplayData(featuredProducts)).data("heroData", heroData)
                .data("themeTokens", themeTokens).data("cartItemCount", 0) // TODO: Load from session/cart service
                .data("locale", locale).data("currentYear", Year.now().getValue()).data("pageTitle", "Home").render();
    }

    /**
     * Build hero section data. Currently stubbed with default values. Will be replaced with CMS-driven content in
     * future iterations.
     *
     * @param tenantInfo
     *            current tenant
     * @return hero data map
     */
    private Map<String, Object> buildHeroData(TenantInfo tenantInfo) {
        Map<String, Object> hero = new HashMap<>();
        hero.put("title", "Discover Quality Products");
        hero.put("subtitle", "Shop our curated selection of premium items");
        hero.put("primaryCtaText", "Shop Now");
        hero.put("primaryCtaUrl", "/category/all");
        hero.put("imageUrl", null); // TODO: Load from tenant config or CMS
        hero.put("eyebrow", null);
        hero.put("secondaryCtaText", null);
        hero.put("secondaryCtaUrl", null);
        hero.put("imageAlt", null);
        return hero;
    }

    /**
     * Build theme tokens for CSS custom properties. Currently uses default palette. Will be replaced with tenant_theme
     * table data in future iterations.
     *
     * @param tenantInfo
     *            current tenant
     * @return theme tokens map
     */
    private Map<String, String> buildThemeTokens(TenantInfo tenantInfo) {
        // TODO: Load from tenant_theme table
        // For now, return empty map to use defaults from tailwind.config.js
        return new HashMap<>();
    }

    /**
     * Map Product entities to template-friendly display objects. Converts domain models to simple DTOs with
     * pre-formatted prices and display-ready fields.
     *
     * @param products
     *            product entities
     * @return list of display data maps
     */
    private List<Map<String, Object>> mapProductsToDisplayData(List<Product> products) {
        return products.stream().map(this::mapProductToDisplayData).collect(Collectors.toList());
    }

    /**
     * Map single Product to display data.
     *
     * @param product
     *            product entity
     * @return display data map
     */
    private Map<String, Object> mapProductToDisplayData(Product product) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", product.id);
        data.put("slug", product.slug);
        data.put("name", product.name);
        data.put("description", product.description);
        data.put("imageUrl", getProductImageUrl(product));
        data.put("price", formatPrice(product)); // TODO: Use MoneyFormatter
        data.put("compareAtPrice", null); // TODO: Implement variant-based pricing
        data.put("onSale", false); // TODO: Implement sale logic
        data.put("isNew", isNewProduct(product));
        data.put("inStock", true); // TODO: Integrate with InventoryService
        data.put("hasVariants", false); // TODO: Check if product has multiple variants
        data.put("categoryName", getCategoryName(product));
        data.put("averageRating", null); // TODO: Integrate with ReviewService
        data.put("reviewCount", 0); // TODO: Integrate with ReviewService
        return data;
    }

    private List<Map<String, Object>> mapCategoriesToDisplayData(List<Category> categories) {
        return categories.stream().map(this::mapCategoryToDisplayData).collect(Collectors.toList());
    }

    private Map<String, Object> mapCategoryToDisplayData(Category category) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", category.name);
        data.put("slug", category.slug);
        data.put("imageUrl", null);
        return data;
    }

    /**
     * Get primary product image URL.
     *
     * @param product
     *            product entity
     * @return image URL or null
     */
    private String getProductImageUrl(Product product) {
        // TODO: Parse metadata JSONB for images array and return first image
        return null;
    }

    /**
     * Format product price for display.
     *
     * @param product
     *            product entity
     * @return formatted price string
     */
    private String formatPrice(Product product) {
        // TODO: Get base variant price and format with MoneyFormatter
        return "$0.00";
    }

    /**
     * Check if product is considered "new" (created within last 30 days).
     *
     * @param product
     *            product entity
     * @return true if new
     */
    private boolean isNewProduct(Product product) {
        if (product.createdAt == null) {
            return false;
        }
        return product.createdAt.isAfter(java.time.OffsetDateTime.now().minusDays(30));
    }

    /**
     * Get category name from product's primary category.
     *
     * @param product
     *            product entity
     * @return category name or null
     */
    private String getCategoryName(Product product) {
        // TODO: Load category relationships and return primary category name
        return null;
    }
}
