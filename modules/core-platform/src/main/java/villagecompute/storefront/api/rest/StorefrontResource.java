package villagecompute.storefront.api.rest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.FeatureToggle;
import villagecompute.storefront.services.ThemeProvider;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;
import villagecompute.storefront.util.LocalizationService;

import io.micrometer.core.annotation.Timed;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.vertx.ext.web.RoutingContext;

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
    private static final UUID SAMPLE_CART_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SAMPLE_VARIANT_PRIMARY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SAMPLE_VARIANT_SECONDARY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final List<Integer> STAR_RATING_SCALE = List.of(1, 2, 3, 4, 5);
    private final Map<UUID, List<Map<String, Object>>> featuredProductCache = new ConcurrentHashMap<>();

    @Inject
    CatalogService catalogService;

    @Inject
    LocalizationService localizationService;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    ThemeProvider themeProvider;

    /**
     * CheckedTemplate for type-safe Qute template references. Templates are located in src/main/resources/templates/
     */
    @CheckedTemplate(
            requireTypeSafeExpressions = false)
    public static class Templates {
        public static native TemplateInstance index();

        public static native TemplateInstance catalog();

        public static native TemplateInstance product();

        public static native TemplateInstance cart();

        public static native TemplateInstance checkout();

        public static native TemplateInstance account();
    }

    private record LocaleResolution(String locale, NewCookie cookie) {
    }

    /**
     * Parse locale from Accept-Language header. Falls back to "en" if header is missing or unsupported.
     *
     * @param headers
     *            HTTP headers
     * @return locale string (e.g., "en", "es")
     */
    private String parseLocaleFromHeaders(HttpHeaders headers) {
        if (headers == null) {
            return "en";
        }

        String acceptLanguage = headers.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return "en";
        }

        // Simple parsing: take first language tag before comma and semicolon
        // Example: "en-US,en;q=0.9,es;q=0.8" -> "en"
        String firstLang = acceptLanguage.split(",")[0].split(";")[0].trim().toLowerCase();

        // Extract language code (before hyphen)
        String langCode = firstLang.split("-")[0];

        // Support only en and es for now
        if ("es".equals(langCode)) {
            return "es";
        }

        return "en"; // Default fallback
    }

    /**
     * Resolve locale using priority: 1) lang query param, 2) visitor_locale cookie, 3) Accept-Language header.
     *
     * <p>
     * The visitor_locale cookie stores the user's preferred language across sessions. When lang query param is present,
     * it takes precedence and triggers cookie update.
     *
     * @param langParam
     *            optional lang query parameter (e.g., ?lang=es)
     * @param headers
     *            HTTP headers containing cookies and Accept-Language
     * @return resolved locale string ("en" or "es")
     */
    private String resolveLocale(String langParam, HttpHeaders headers) {
        // 1. Explicit lang parameter has highest priority
        if (hasText(langParam)) {
            return "es".equalsIgnoreCase(langParam) ? "es" : "en";
        }

        // 2. Check visitor_locale cookie
        if (headers != null && headers.getCookies() != null) {
            Cookie localeCookie = headers.getCookies().get("visitor_locale");
            if (localeCookie != null && hasText(localeCookie.getValue())) {
                String cookieLocale = localeCookie.getValue();
                if ("es".equalsIgnoreCase(cookieLocale)) {
                    return "es";
                } else if ("en".equalsIgnoreCase(cookieLocale)) {
                    return "en";
                }
            }
        }

        // 3. Fall back to Accept-Language header
        return parseLocaleFromHeaders(headers);
    }

    /**
     * Create visitor_locale cookie for the specified locale.
     *
     * <p>
     * Cookie is set for 1 year and applies to all paths. This allows the storefront to remember visitor's language
     * preference across sessions.
     *
     * @param locale
     *            locale string ("en" or "es")
     * @return NewCookie for response
     */
    private NewCookie createLocaleCookie(String locale) {
        return new NewCookie.Builder("visitor_locale").value(locale).path("/").maxAge(365 * 24 * 60 * 60) // 1 year
                .httpOnly(false) // Allow JavaScript access for client-side
                                 // features
                .sameSite(NewCookie.SameSite.LAX).build();
    }

    /**
     * Determine if locale cookie should be updated. Returns true when lang query param is present and differs from
     * current cookie value.
     *
     * @param langParam
     *            lang query parameter
     * @param headers
     *            HTTP headers
     * @param resolvedLocale
     *            the resolved locale value
     * @return true if cookie should be set
     */
    private boolean shouldSetLocaleCookie(String langParam, HttpHeaders headers, String resolvedLocale) {
        if (!hasText(langParam)) {
            return false; // Only set cookie when explicitly requested via query param
        }

        // Check if cookie already has this value
        if (headers != null && headers.getCookies() != null) {
            Cookie existingCookie = headers.getCookies().get("visitor_locale");
            if (existingCookie != null && resolvedLocale.equals(existingCookie.getValue())) {
                return false; // Cookie already has correct value
            }
        }

        return true;
    }

    private LocaleResolution prepareLocaleResolution(String langParam, HttpHeaders headers,
            RoutingContext routingContext) {
        String locale = resolveLocale(langParam, headers);
        setRequestLocaleAttribute(locale, routingContext);
        NewCookie cookie = shouldSetLocaleCookie(langParam, headers, locale) ? createLocaleCookie(locale) : null;
        return new LocaleResolution(locale, cookie);
    }

    private void setRequestLocaleAttribute(String locale, RoutingContext routingContext) {
        if (!hasText(locale) || routingContext == null) {
            return;
        }
        // Stored for downstream filters/components (e.g., translation bundles)
        routingContext.put("storefront.locale", locale);
        routingContext.put("storefront.language", locale);
    }

    private Response buildHtmlResponse(TemplateInstance templateInstance, LocaleResolution localeResolution) {
        String html = templateInstance.render();
        Response.ResponseBuilder builder = Response.ok(html, MediaType.TEXT_HTML_TYPE);
        if (localeResolution != null) {
            if (localeResolution.cookie() != null) {
                builder.cookie(localeResolution.cookie());
            }
            if (hasText(localeResolution.locale())) {
                builder.header(HttpHeaders.CONTENT_LANGUAGE, localeResolution.locale());
            }
        }
        return builder.build();
    }

    private List<Map<String, Object>> buildLanguageOptions(String locale, UriInfo uriInfo) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(buildLanguageOption("EN", "en", locale, uriInfo));
        options.add(buildLanguageOption("ES", "es", locale, uriInfo));
        return options;
    }

    private Map<String, Object> buildLanguageOption(String label, String value, String locale, UriInfo uriInfo) {
        UriBuilder builder = uriInfo != null ? uriInfo.getRequestUriBuilder() : UriBuilder.fromPath("/");
        URI target = builder.clone().replaceQueryParam("lang", value).build();
        Map<String, Object> option = new HashMap<>();
        option.put("label", label);
        option.put("value", value);
        option.put("active", value.equalsIgnoreCase(locale));
        option.put("url", target.toString());
        return option;
    }

    /**
     * Homepage route - renders hero, categories, and featured products.
     *
     * @param headers
     *            HTTP headers for locale detection
     * @return rendered HTML
     */
    @GET
    @Timed(
            value = "storefront.homepage.render",
            description = "Homepage render time")
    public Response index(@QueryParam("lang") String lang, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context RoutingContext routingContext) {
        long startTime = System.currentTimeMillis();

        TenantInfo tenantInfo = TenantContext.getCurrentTenant();
        LOG.infof("Rendering homepage - tenantId=%s, domain=%s", tenantInfo.tenantId(), tenantInfo.subdomain());

        List<Map<String, Object>> rootCategoryDisplay = loadRootCategoryDisplay();

        List<Map<String, Object>> featuredProducts = loadFeaturedProductDisplay(tenantInfo);
        Map<String, Object> heroData = buildHeroData(tenantInfo);
        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);

        LocaleResolution localeResolution = prepareLocaleResolution(lang, headers, routingContext);
        String locale = localeResolution.locale();
        Map<String, String> messages = localizationService.loadMessages(locale);
        List<Map<String, Object>> languageOptions = buildLanguageOptions(locale, uriInfo);
        Map<String, Object> cartContext = buildSampleCartContext();
        List<Map<String, Object>> cartItems = getCartItems(cartContext);
        int cartCount = extractCartCount(cartContext);

        long renderTime = System.currentTimeMillis() - startTime;
        LOG.infof("Homepage rendered in %d ms - tenantId=%s", renderTime, tenantInfo.tenantId());

        TemplateInstance templateInstance = Templates.index().data("tenantName", tenantInfo.name())
                .data("tenantSubdomain", tenantInfo.subdomain()).data("tenantLogo", null).data("tenantTagline", null)
                .data("seoTitle", null).data("seoDescription", null).data("canonicalUrl", null).data("msg", messages)
                .data("rootCategories", rootCategoryDisplay).data("collections", List.of())
                .data("featuredProducts", featuredProducts).data("heroData", heroData).data("themeTokens", themeTokens)
                .data("languageOptions", languageOptions).data("cartItems", cartItems)
                .data("cartSubtotal", getCartField(cartContext, "subtotal", "$0.00")).data("cartItemCount", cartCount)
                .data("locale", locale).data("currentYear", Year.now().getValue()).data("pageTitle", "Home");
        return buildHtmlResponse(templateInstance, localeResolution);
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

    private List<Map<String, Object>> loadFeaturedProductDisplay(TenantInfo tenantInfo) {
        UUID tenantId = tenantInfo != null ? tenantInfo.tenantId() : null;
        try {
            List<Product> featuredProducts = catalogService.listActiveProducts(0, 8);
            if (featuredProducts != null && !featuredProducts.isEmpty()) {
                List<Map<String, Object>> displayData = List.copyOf(mapProductsToDisplayData(featuredProducts));
                cacheFeaturedProducts(tenantId, displayData);
                return displayData;
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to load featured products for tenant %s. Serving cached fallback.", tenantId);
        }

        if (tenantId != null) {
            List<Map<String, Object>> cached = featuredProductCache.get(tenantId);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }

        return buildSampleFeaturedProducts();
    }

    private void cacheFeaturedProducts(UUID tenantId, List<Map<String, Object>> displayData) {
        if (tenantId == null || displayData == null || displayData.isEmpty()) {
            return;
        }
        featuredProductCache.put(tenantId, List.copyOf(displayData));
    }

    private List<Map<String, Object>> buildSampleFeaturedProducts() {
        return List.of(
                createSampleProduct("11111111-2222-3333-4444-555555555555", "artisan-tote", "Artisan Leather Tote",
                        "$58.00", "$72.00", true, "Bags", "https://placehold.co/600x600?text=Leather+Tote"),
                createSampleProduct("55555555-6666-7777-8888-999999999999", "wellness-candle", "Wellness Candle Duo",
                        "$32.00", null, false, "Home", "https://placehold.co/600x600?text=Candle+Duo"),
                createSampleProduct("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "stoneware-mug", "Stoneware Mug Set",
                        "$28.00", "$34.00", true, "Kitchen", "https://placehold.co/600x600?text=Stoneware+Mug"),
                createSampleProduct("99999999-8888-7777-6666-555555555555", "linen-shirt", "Relaxed Linen Shirt",
                        "$64.00", null, false, "Apparel", "https://placehold.co/600x600?text=Linen+Shirt"));
    }

    private Map<String, Object> createSampleProduct(String id, String slug, String name, String price,
            String compareAtPrice, boolean onSale, String categoryName, String imageUrl) {
        Map<String, Object> product = new HashMap<>();
        product.put("id", id);
        product.put("slug", slug);
        product.put("name", name);
        product.put("description", "Curated sample product for storefront preview.");
        product.put("imageUrl", imageUrl);
        product.put("price", price);
        product.put("compareAtPrice", compareAtPrice);
        product.put("onSale", onSale);
        product.put("isNew", true);
        product.put("inStock", true);
        product.put("hasVariants", false);
        product.put("categoryName", categoryName);
        product.put("averageRating", 4.7);
        product.put("reviewCount", 128);
        applyRatingScale(product);
        return product;
    }

    /**
     * Build theme tokens for CSS custom properties. Delegates to ThemeProvider for tenant-specific theming with
     * caching.
     *
     * @param tenantInfo
     *            current tenant
     * @return theme tokens map
     */
    private Map<String, String> buildThemeTokens(TenantInfo tenantInfo) {
        if (tenantInfo == null) {
            return themeProvider.getThemeTokens("default");
        }
        return themeProvider.getThemeTokens(tenantInfo.subdomain());
    }

    private Map<String, String> buildCategoryLookup(List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return Collections.emptyMap();
        }
        return categories.stream().filter(category -> hasText(category.slug) && hasText(category.name))
                .collect(Collectors.toMap(category -> category.slug, category -> category.name,
                        (existing, ignored) -> existing));
    }

    private Set<String> buildSelectedCategorySlugs(String categorySlug, UriInfo uriInfo) {
        Set<String> selected = new HashSet<>();
        if (hasText(categorySlug) && !"all".equalsIgnoreCase(categorySlug)) {
            selected.add(categorySlug);
        }
        if (uriInfo != null) {
            MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
            if (params != null) {
                List<String> categoryParams = params.get("category");
                if (categoryParams != null) {
                    categoryParams.stream().filter(this::hasText).forEach(selected::add);
                }
            }
        }
        return selected;
    }

    private List<Map<String, Object>> buildCategoryFilterOptions(List<Category> categories,
            Set<String> selectedCategorySlugs) {
        if (categories == null || categories.isEmpty()) {
            return List.of();
        }

        return categories.stream().filter(category -> hasText(category.slug)).map(category -> {
            Map<String, Object> option = new HashMap<>();
            option.put("name", category.name);
            option.put("slug", category.slug);
            option.put("selected", selectedCategorySlugs.contains(category.slug));
            option.put("count", null);
            return option;
        }).collect(Collectors.toList());
    }

    private String resolveCategoryName(String categorySlug, Map<String, String> categoryLookup,
            Map<String, String> messages) {
        if (!hasText(categorySlug) || "all".equalsIgnoreCase(categorySlug)) {
            return messages.getOrDefault("all_products", "All Products");
        }
        return categoryLookup.getOrDefault(categorySlug, categorySlug);
    }

    private void addFiltersFromParam(MultivaluedMap<String, String> params, String paramKey,
            Map<String, String> labelLookup, List<Map<String, Object>> activeFilters, Set<String> seen) {
        List<String> values = params.get(paramKey);
        if (values == null) {
            return;
        }
        values.stream().filter(this::hasText).forEach(value -> {
            String label = labelLookup != null ? labelLookup.getOrDefault(value, value) : value;
            addActiveFilter(activeFilters, seen, paramKey, value, label);
        });
    }

    private void addBooleanFilter(List<Map<String, Object>> activeFilters, Set<String> seen, String key,
            MultivaluedMap<String, String> params, String label) {
        String value = params.getFirst(key);
        if (isTruthy(value)) {
            addActiveFilter(activeFilters, seen, key, "true", label);
        }
    }

    private void addPriceFilterPill(List<Map<String, Object>> activeFilters, Set<String> seen, String key, String value,
            String labelPrefix) {
        if (!hasText(value)) {
            return;
        }
        String label = String.format("%s: $%s", labelPrefix, value);
        addActiveFilter(activeFilters, seen, key, value, label);
    }

    private void addActiveFilter(List<Map<String, Object>> activeFilters, Set<String> seen, String key, String value,
            String label) {
        String dedupeKey = key + ":" + value;
        if (seen.contains(dedupeKey)) {
            return;
        }
        seen.add(dedupeKey);

        Map<String, Object> filter = new HashMap<>();
        filter.put("key", key);
        filter.put("value", value);
        filter.put("label", label);
        activeFilters.add(filter);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Integer parseInteger(String value) {
        if (!hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean isTruthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
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

    private void applyRatingScale(Map<String, Object> product) {
        if (product != null) {
            product.putIfAbsent("ratingScale", STAR_RATING_SCALE);
        }
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
        applyRatingScale(data);
        return data;
    }

    private List<Map<String, Object>> mapCategoriesToDisplayData(List<Category> categories) {
        return categories.stream().map(this::mapCategoryToDisplayData).collect(Collectors.toList());
    }

    private List<Map<String, Object>> loadRootCategoryDisplay() {
        List<Category> rootCategories = catalogService.getRootCategories();
        LOG.debugf("Loaded %d root categories", rootCategories.size());
        List<Map<String, Object>> display = mapCategoriesToDisplayData(rootCategories);
        if (display.isEmpty()) {
            return buildSampleRootCategories();
        }
        return display;
    }

    private Map<String, Object> mapCategoryToDisplayData(Category category) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", category.name);
        data.put("slug", category.slug);
        data.put("imageUrl", null);
        return data;
    }

    private List<Map<String, Object>> buildSampleRootCategories() {
        return List.of(createCategoryDisplay("bags", "Bags"), createCategoryDisplay("apparel", "Apparel"),
                createCategoryDisplay("home", "Home Goods"), createCategoryDisplay("wellness", "Wellness"));
    }

    private Map<String, Object> createCategoryDisplay(String slug, String name) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("slug", slug);
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

    /**
     * Catalog browsing route - renders product grid with filters and pagination.
     *
     * @param categorySlug
     *            optional category slug for filtering
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @param sortBy
     *            sort order
     * @param uriInfo
     *            URI info for query params
     * @return rendered HTML
     */
    @GET
    @Path("/category/{slug}")
    @Timed(
            value = "storefront.catalog.render",
            description = "Catalog page render time")
    public Response catalog(@PathParam("slug") String categorySlug, @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("12") int size,
            @QueryParam("sort") @DefaultValue("relevance") String sortBy, @QueryParam("lang") String lang,
            @Context UriInfo uriInfo, @Context HttpHeaders headers, @Context RoutingContext routingContext) {

        long startTime = System.currentTimeMillis();
        TenantInfo tenantInfo = TenantContext.getCurrentTenant();

        LOG.infof("Rendering catalog - tenantId=%s, category=%s, page=%d, size=%d, sort=%s", tenantInfo.tenantId(),
                categorySlug, page, size, sortBy);

        LocaleResolution localeResolution = prepareLocaleResolution(lang, headers, routingContext);
        String locale = localeResolution.locale();
        Map<String, String> messages = localizationService.loadMessages(locale);
        List<Map<String, Object>> languageOptions = buildLanguageOptions(locale, uriInfo);
        Map<String, Object> cartContext = buildSampleCartContext();
        List<Map<String, Object>> cartItems = getCartItems(cartContext);
        int cartCount = extractCartCount(cartContext);

        // Fetch products with pagination
        CatalogService.CatalogSearchResult searchResult = catalogService.listProducts(categorySlug, null, page, size);
        List<Product> products = searchResult.products();
        long totalProducts = searchResult.totalItems();

        // Fetch available categories for the filter panel
        List<Category> filterCategories = catalogService.getRootCategories();
        List<Map<String, Object>> rootCategoryDisplay = mapCategoriesToDisplayData(filterCategories);
        Map<String, String> categoryLookup = buildCategoryLookup(filterCategories);
        Set<String> selectedCategorySlugs = buildSelectedCategorySlugs(categorySlug, uriInfo);

        // Calculate pagination
        int totalPages = (int) Math.ceil((double) totalProducts / size);
        int totalPagesDisplay = Math.max(totalPages, 1);
        int currentPageDisplay = Math.min(page + 1, totalPagesDisplay);

        // Build category breadcrumbs and info
        String categoryName = resolveCategoryName(categorySlug, categoryLookup, messages);
        String categoryDescription = null; // TODO: Load from category entity
        List<Map<String, Object>> breadcrumbs = buildBreadcrumbs(categorySlug, categoryName);

        // Build filter data
        Map<String, Object> filters = buildFilterData(uriInfo, filterCategories, selectedCategorySlugs);

        // Build active filters from query params
        List<Map<String, Object>> activeFilters = buildActiveFilters(uriInfo, messages, categoryLookup);

        // Build page numbers for pagination
        List<Integer> pageNumbers = buildPageNumbers(page, totalPages);

        // Build query param strings for pagination links
        String sortParam = (sortBy != null && !sortBy.isBlank() && !"relevance".equals(sortBy))
                ? "&sort=" + urlEncode(sortBy)
                : "";
        String filterParams = buildFilterParamString(uriInfo);

        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);

        long renderTime = System.currentTimeMillis() - startTime;
        LOG.infof("Catalog rendered in %d ms - tenantId=%s, products=%d", renderTime, tenantInfo.tenantId(),
                products.size());

        TemplateInstance templateInstance = Templates.catalog().data("tenantName", tenantInfo.name())
                .data("tenantSubdomain", tenantInfo.subdomain()).data("msg", messages).data("themeTokens", themeTokens)
                .data("categoryName", categoryName).data("categoryDescription", categoryDescription)
                .data("breadcrumbs", breadcrumbs).data("products", mapProductsToDisplayData(products))
                .data("totalProducts", totalProducts).data("currentPage", page)
                .data("currentPageDisplay", currentPageDisplay).data("totalPages", totalPages)
                .data("totalPagesDisplay", totalPagesDisplay).data("pageNumbers", pageNumbers).data("sortBy", sortBy)
                .data("sortParam", sortParam).data("filterParams", filterParams).data("filters", filters)
                .data("activeFilters", activeFilters).data("rootCategories", rootCategoryDisplay)
                .data("languageOptions", languageOptions).data("cartItems", cartItems)
                .data("cartSubtotal", getCartField(cartContext, "subtotal", "$0.00")).data("cartItemCount", cartCount)
                .data("locale", locale).data("currentYear", Year.now().getValue()).data("pageTitle", categoryName)
                .data("seoDescription", categoryDescription);
        return buildHtmlResponse(templateInstance, localeResolution);
    }

    /**
     * Build breadcrumb navigation for catalog page.
     *
     * @param categorySlug
     *            category slug
     * @return breadcrumb list
     */
    private List<Map<String, Object>> buildBreadcrumbs(String categorySlug, String categoryDisplayName) {
        List<Map<String, Object>> breadcrumbs = new ArrayList<>();

        // For now, just add the current category
        // TODO: Build full category hierarchy from database
        if (categorySlug != null && !"all".equalsIgnoreCase(categorySlug)) {
            Map<String, Object> crumb = new HashMap<>();
            crumb.put("name", categoryDisplayName != null ? categoryDisplayName : categorySlug);
            crumb.put("url", "/category/" + categorySlug);
            crumb.put("isLast", true);
            breadcrumbs.add(crumb);
        }

        return breadcrumbs;
    }

    /**
     * Build filter data structure for filter panel.
     *
     * @param uriInfo
     *            URI info for current query params
     * @return filter data map
     */
    private Map<String, Object> buildFilterData(UriInfo uriInfo, List<Category> categories,
            Set<String> selectedCategorySlugs) {
        Map<String, Object> filters = new HashMap<>();
        MultivaluedMap<String, String> queryParams = uriInfo != null ? uriInfo.getQueryParameters() : null;
        String selectedPriceMin = queryParams != null ? queryParams.getFirst("price_min") : null;
        String selectedPriceMax = queryParams != null ? queryParams.getFirst("price_max") : null;

        // TODO: Load actual filter options from database/aggregations
        // For now, return minimal structure

        // Price range filter (stubbed)
        Map<String, Object> priceRange = new HashMap<>();
        priceRange.put("min", 0);
        priceRange.put("max", 1000);
        priceRange.put("selectedMin", parseInteger(selectedPriceMin));
        priceRange.put("selectedMax", parseInteger(selectedPriceMax));
        priceRange.put("quickRanges", List.of());
        filters.put("priceRange", priceRange);

        // Availability filter
        Map<String, Object> availability = new HashMap<>();
        availability.put("inStock", isTruthy(queryParams != null ? queryParams.getFirst("in_stock") : null));
        availability.put("onSale", isTruthy(queryParams != null ? queryParams.getFirst("on_sale") : null));
        filters.put("availability", availability);

        filters.put("categories", buildCategoryFilterOptions(categories, selectedCategorySlugs));
        filters.put("brands", List.of());
        filters.put("tags", List.of());

        return filters;
    }

    /**
     * Build list of active filters from query parameters.
     *
     * @param uriInfo
     *            URI info
     * @return list of active filter objects
     */
    private List<Map<String, Object>> buildActiveFilters(UriInfo uriInfo, Map<String, String> messages,
            Map<String, String> categoryLookup) {
        List<Map<String, Object>> activeFilters = new ArrayList<>();
        if (uriInfo == null) {
            return activeFilters;
        }

        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        if (params == null || params.isEmpty()) {
            return activeFilters;
        }

        Set<String> seen = new HashSet<>();
        addFiltersFromParam(params, "category", categoryLookup, activeFilters, seen);
        addFiltersFromParam(params, "brand", null, activeFilters, seen);
        addFiltersFromParam(params, "tag", null, activeFilters, seen);

        addBooleanFilter(activeFilters, seen, "in_stock", params, messages.getOrDefault("in_stock_only", "In Stock"));
        addBooleanFilter(activeFilters, seen, "on_sale", params, messages.getOrDefault("on_sale_only", "On Sale"));

        addPriceFilterPill(activeFilters, seen, "price_min", params.getFirst("price_min"),
                messages.getOrDefault("price_min", "Min"));
        addPriceFilterPill(activeFilters, seen, "price_max", params.getFirst("price_max"),
                messages.getOrDefault("price_max", "Max"));

        List<String> quickRanges = params.get("price_range");
        if (quickRanges != null) {
            quickRanges.stream().filter(this::hasText).forEach(value -> {
                String label = messages.getOrDefault("filter_price", "Price Range") + ": " + value;
                addActiveFilter(activeFilters, seen, "price_range", value, label);
            });
        }

        return activeFilters;
    }

    /**
     * Build page numbers for pagination UI.
     *
     * @param currentPage
     *            current page (0-indexed)
     * @param totalPages
     *            total number of pages
     * @return list of page numbers (or -1 for ellipsis)
     */
    private List<Integer> buildPageNumbers(int currentPage, int totalPages) {
        List<Integer> pages = new ArrayList<>();

        if (totalPages <= 7) {
            // Show all pages
            for (int i = 0; i < totalPages; i++) {
                pages.add(i);
            }
        } else {
            // Show first, last, current, and nearby pages with ellipsis
            pages.add(0); // First page

            if (currentPage > 3) {
                pages.add(-1); // Ellipsis
            }

            // Show 2 pages before and after current
            for (int i = Math.max(1, currentPage - 2); i <= Math.min(totalPages - 2, currentPage + 2); i++) {
                pages.add(i);
            }

            if (currentPage < totalPages - 4) {
                pages.add(-1); // Ellipsis
            }

            pages.add(totalPages - 1); // Last page
        }

        return pages;
    }

    /**
     * Build filter parameter string for pagination links.
     *
     * @param uriInfo
     *            URI info
     * @return filter param string (e.g., "&category=foo&brand=bar")
     */
    private String buildFilterParamString(UriInfo uriInfo) {
        if (uriInfo == null) {
            return "";
        }
        MultivaluedMap<String, String> params = uriInfo.getQueryParameters();
        if (params == null || params.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        params.forEach((key, values) -> {
            if ("page".equals(key) || "sort".equals(key) || values == null) {
                return;
            }
            for (String value : values) {
                if (!hasText(value)) {
                    continue;
                }
                builder.append("&").append(key).append("=").append(urlEncode(value));
            }
        });

        return builder.toString();
    }

    /**
     * Product detail route - renders product with variants, images, and reviews.
     *
     * @param productSlug
     *            product slug
     * @param headers
     *            HTTP headers for locale detection
     * @return rendered HTML
     */
    @GET
    @Path("/product/{slug}")
    @Timed(
            value = "storefront.product.render",
            description = "Product page render time")
    public Response product(@PathParam("slug") String productSlug, @QueryParam("lang") String lang,
            @Context HttpHeaders headers, @Context UriInfo uriInfo, @Context RoutingContext routingContext) {
        TenantInfo tenantInfo = TenantContext.getCurrentTenant();
        LocaleResolution localeResolution = prepareLocaleResolution(lang, headers, routingContext);
        String locale = localeResolution.locale();
        Map<String, String> messages = localizationService.loadMessages(locale);
        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);
        List<Map<String, Object>> languageOptions = buildLanguageOptions(locale, uriInfo);
        Map<String, Object> cartContext = buildSampleCartContext();
        List<Map<String, Object>> cartItems = getCartItems(cartContext);
        int cartCount = extractCartCount(cartContext);
        List<Map<String, Object>> rootCategoryDisplay = loadRootCategoryDisplay();

        Map<String, Object> productData;
        List<ProductVariant> variants = List.of();
        Optional<Product> productOptional = catalogService.getProductBySlug(productSlug);
        if (productOptional.isPresent()) {
            Product product = productOptional.get();
            variants = catalogService.getProductVariants(product.id);
            productData = mapProductDetailData(product, variants);
        } else {
            productData = buildSampleProductDetail(productSlug);
        }

        List<Map<String, Object>> breadcrumbs = buildProductPageBreadcrumbs(productData);
        List<Product> relatedDomainProducts = catalogService.listActiveProducts(0, 4);
        List<Map<String, Object>> relatedProducts = mapProductsToDisplayData(relatedDomainProducts);
        if (relatedProducts.isEmpty()) {
            relatedProducts = buildSampleRelatedProducts();
        }

        TemplateInstance templateInstance = Templates.product().data("tenantName", tenantInfo.name())
                .data("tenantSubdomain", tenantInfo.subdomain()).data("msg", messages).data("themeTokens", themeTokens)
                .data("product", productData).data("breadcrumbs", breadcrumbs).data("relatedProducts", relatedProducts)
                .data("rootCategories", rootCategoryDisplay).data("languageOptions", languageOptions)
                .data("cartItems", cartItems).data("cartSubtotal", getCartField(cartContext, "subtotal", "$0.00"))
                .data("cartItemCount", cartCount).data("locale", locale).data("currentYear", Year.now().getValue())
                .data("pageTitle", productData.get("name")).data("seoDescription",
                        productData.getOrDefault("seoDescription", messages.get("product_meta_description")));
        return buildHtmlResponse(templateInstance, localeResolution);
    }

    /**
     * Shopping cart route.
     *
     * @param headers
     *            HTTP headers for locale detection
     * @return rendered HTML
     */
    @GET
    @Path("/cart")
    @Timed(
            value = "storefront.cart.render",
            description = "Cart page render time")
    public Response cart(@QueryParam("lang") String lang, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context RoutingContext routingContext) {
        TenantInfo tenantInfo = TenantContext.getCurrentTenant();
        LocaleResolution localeResolution = prepareLocaleResolution(lang, headers, routingContext);
        String locale = localeResolution.locale();
        Map<String, String> messages = localizationService.loadMessages(locale);
        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);
        Map<String, Object> cartContext = buildSampleCartContext();
        List<Map<String, Object>> cartItems = getCartItems(cartContext);
        int cartCount = extractCartCount(cartContext);
        List<Map<String, Object>> languageOptions = buildLanguageOptions(locale, uriInfo);
        List<Map<String, Object>> rootCategoryDisplay = loadRootCategoryDisplay();

        TemplateInstance templateInstance = Templates.cart().data("tenantName", tenantInfo.name())
                .data("tenantSubdomain", tenantInfo.subdomain()).data("msg", messages).data("themeTokens", themeTokens)
                .data("cartItems", cartItems).data("cartSubtotal", getCartField(cartContext, "subtotal", "$0.00"))
                .data("cartTax",
                        getCartField(cartContext, "tax",
                                messages.getOrDefault("calculated_at_checkout", "Calculated at checkout")))
                .data("cartTotal", getCartField(cartContext, "total", "$0.00"))
                .data("cartShipping",
                        getCartField(cartContext, "shipping",
                                messages.getOrDefault("calculated_at_checkout", "Calculated at checkout")))
                .data("languageOptions", languageOptions).data("rootCategories", rootCategoryDisplay)
                .data("cartItemCount", cartCount).data("locale", locale).data("currentYear", Year.now().getValue())
                .data("pageTitle", "Shopping Cart");
        return buildHtmlResponse(templateInstance, localeResolution);
    }

    /**
     * Checkout route.
     *
     * @param headers
     *            HTTP headers for locale detection
     * @return rendered HTML
     */
    @GET
    @Path("/checkout")
    @Timed(
            value = "storefront.checkout.render",
            description = "Checkout page render time")
    public Response checkout(@QueryParam("lang") String lang, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context RoutingContext routingContext) {
        TenantInfo tenantInfo = TenantContext.getCurrentTenant();
        LocaleResolution localeResolution = prepareLocaleResolution(lang, headers, routingContext);
        String locale = localeResolution.locale();
        Map<String, String> messages = localizationService.loadMessages(locale);
        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);
        Map<String, Object> cartContext = buildSampleCartContext();
        List<Map<String, Object>> cartItems = getCartItems(cartContext);
        int cartCount = extractCartCount(cartContext);
        List<Map<String, Object>> languageOptions = buildLanguageOptions(locale, uriInfo);
        List<Map<String, Object>> rootCategoryDisplay = loadRootCategoryDisplay();

        TemplateInstance templateInstance = Templates.checkout().data("tenantName", tenantInfo.name())
                .data("tenantSubdomain", tenantInfo.subdomain()).data("msg", messages).data("themeTokens", themeTokens)
                .data("cartItems", cartItems).data("cartSubtotal", getCartField(cartContext, "subtotal", "$0.00"))
                .data("cartTax",
                        getCartField(cartContext, "tax",
                                messages.getOrDefault("calculated_next", "Calculated in next step")))
                .data("cartTotal", getCartField(cartContext, "total", "$0.00"))
                .data("selectedShippingRate",
                        getCartField(cartContext, "shipping",
                                messages.getOrDefault("calculated_next", "Calculated in next step")))
                .data("shippingRates", List.of())
                .data("cartId", getCartField(cartContext, "cartId", SAMPLE_CART_ID.toString()))
                .data("languageOptions", languageOptions).data("rootCategories", rootCategoryDisplay)
                .data("cartItemCount", cartCount).data("locale", locale).data("currentYear", Year.now().getValue())
                .data("pageTitle", "Checkout");
        return buildHtmlResponse(templateInstance, localeResolution);
    }

    /**
     * Account dashboard route.
     *
     * @param headers
     *            HTTP headers for locale detection
     * @return rendered HTML
     */
    @GET
    @Path("/account")
    @Timed(
            value = "storefront.account.render",
            description = "Account page render time")
    public Response account(@QueryParam("lang") String lang, @Context HttpHeaders headers, @Context UriInfo uriInfo,
            @Context RoutingContext routingContext) {
        TenantInfo tenantInfo = TenantContext.getCurrentTenant();
        LocaleResolution localeResolution = prepareLocaleResolution(lang, headers, routingContext);
        String locale = localeResolution.locale();
        Map<String, String> messages = localizationService.loadMessages(locale);
        Map<String, String> themeTokens = buildThemeTokens(tenantInfo);
        Map<String, Object> cartContext = buildSampleCartContext();
        List<Map<String, Object>> cartItems = getCartItems(cartContext);
        int cartCount = extractCartCount(cartContext);
        List<Map<String, Object>> languageOptions = buildLanguageOptions(locale, uriInfo);
        List<Map<String, Object>> rootCategoryDisplay = loadRootCategoryDisplay();
        List<Map<String, Object>> recentOrders = buildSampleRecentOrders();
        Map<String, Object> defaultAddress = buildSampleDefaultAddress();
        boolean loyaltyEnabled = featureToggle.isEnabled("storefront.account.loyalty-panel");
        Map<String, Object> loyaltyTier = loyaltyEnabled
                ? Map.of("name", "Gold", "icon", "🌟", "gradient", "from-amber-500 to-rose-500")
                : null;
        Integer loyaltyPoints = loyaltyEnabled ? 1240 : null;
        Integer loyaltyPointsToNext = loyaltyEnabled ? 260 : null;
        Integer loyaltyProgress = loyaltyEnabled ? 72 : null;

        TemplateInstance templateInstance = Templates.account().data("tenantName", tenantInfo.name())
                .data("tenantSubdomain", tenantInfo.subdomain()).data("msg", messages).data("themeTokens", themeTokens)
                .data("customerName", "Avery Lee").data("customerEmail", "avery@example.com")
                .data("customerFirstName", "Avery").data("recentOrders", recentOrders)
                .data("defaultAddress", defaultAddress).data("loyaltyTier", loyaltyTier)
                .data("loyaltyPoints", loyaltyPoints).data("loyaltyPointsToNext", loyaltyPointsToNext)
                .data("loyaltyProgressPercent", loyaltyProgress).data("languageOptions", languageOptions)
                .data("rootCategories", rootCategoryDisplay).data("cartItems", cartItems)
                .data("cartSubtotal", getCartField(cartContext, "subtotal", "$0.00")).data("cartItemCount", cartCount)
                .data("locale", locale).data("currentYear", Year.now().getValue()).data("pageTitle", "My Account");
        return buildHtmlResponse(templateInstance, localeResolution);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getCartItems(Map<String, Object> cartContext) {
        Object items = cartContext.get("items");
        if (items instanceof List<?>) {
            return (List<Map<String, Object>>) items;
        }
        return List.of();
    }

    private int extractCartCount(Map<String, Object> cartContext) {
        Object count = cartContext.get("count");
        if (count instanceof Number number) {
            return number.intValue();
        }
        List<Map<String, Object>> items = getCartItems(cartContext);
        return items.size();
    }

    private String getCartField(Map<String, Object> cartContext, String key, String defaultValue) {
        Object value = cartContext.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private Map<String, Object> buildSampleCartContext() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(buildSampleCartItem("8af1fd2a-4dc7-4f3d-8da1-aa1234560001", "artisan-tote", "Artisan Tote",
                "Camel / Large", "$58.00", "$72.00", 1, false, SAMPLE_VARIANT_PRIMARY.toString()));
        items.add(buildSampleCartItem("8af1fd2a-4dc7-4f3d-8da1-aa1234560002", "consignor-mug", "Consignor Mug Set",
                "Matte Black", "$24.00", null, 2, true, SAMPLE_VARIANT_SECONDARY.toString()));

        Map<String, Object> context = new HashMap<>();
        context.put("items", items);
        context.put("subtotal", "$106.00");
        context.put("tax", "$9.54");
        context.put("shipping", "Calculated at checkout");
        context.put("total", "$115.54");
        context.put("count", items.size());
        context.put("cartId", SAMPLE_CART_ID.toString());
        return context;
    }

    private Map<String, Object> buildSampleCartItem(String id, String slug, String name, String variantTitle,
            String price, String compareAtPrice, int quantity, boolean digital, String variantId) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("slug", slug);
        item.put("name", name);
        item.put("variantTitle", variantTitle);
        item.put("price", price);
        item.put("compareAtPrice", compareAtPrice);
        item.put("quantity", quantity);
        item.put("maxQuantity", 5);
        item.put("isDigital", digital);
        item.put("imageUrl", "https://placehold.co/300x300?text=" + urlEncode(name));
        item.put("variantId", variantId);
        return item;
    }

    private Map<String, Object> mapProductDetailData(Product product, List<ProductVariant> variants) {
        Map<String, Object> detail = buildSampleProductDetail(product.slug);
        detail.put("id", product.id);
        detail.put("slug", product.slug);
        detail.put("name", product.name);
        if (hasText(product.description)) {
            detail.put("description", product.description);
        }
        if (hasText(product.seoDescription)) {
            detail.put("seoDescription", product.seoDescription);
        }
        if (hasText(product.seoTitle)) {
            detail.put("seoTitle", product.seoTitle);
        }
        detail.put("categoryName", getCategoryName(product));

        if (!variants.isEmpty()) {
            ProductVariant primaryVariant = variants.get(0);
            if (primaryVariant.id != null) {
                detail.put("defaultVariantId", primaryVariant.id.toString());
            }
            if (primaryVariant.price != null) {
                detail.put("price", formatMoney(primaryVariant.price));
            }
            if (primaryVariant.compareAtPrice != null && primaryVariant.price != null
                    && primaryVariant.compareAtPrice.compareTo(primaryVariant.price) > 0) {
                detail.put("compareAtPrice", formatMoney(primaryVariant.compareAtPrice));
            }
        }

        detail.putIfAbsent("galleryImages", buildSampleProductImages(product.name));
        detail.putIfAbsent("variantOptions", buildSampleVariantOptions());
        return detail;
    }

    private Map<String, Object> buildSampleProductDetail(String productSlug) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", SAMPLE_VARIANT_PRIMARY.toString());
        data.put("slug", productSlug);
        data.put("name", "Artisan Leather Tote");
        data.put("description", "Handcrafted tote with premium leather, lined interior, and modular straps.");
        data.put("price", "$58.00");
        data.put("compareAtPrice", "$72.00");
        data.put("availability", "in_stock");
        data.put("quantity", 8);
        data.put("averageRating", 4.8);
        data.put("reviewCount", 128);
        data.put("galleryImages", buildSampleProductImages("Artisan Leather Tote"));
        data.put("features",
                List.of("Full-grain leather shell", "Interior laptop sleeve", "Removable crossbody strap"));
        data.put("variantOptions", buildSampleVariantOptions());
        data.put("categoryName", "Featured");
        data.put("categorySlug", "featured");
        data.put("defaultVariantId", SAMPLE_VARIANT_PRIMARY.toString());
        data.put("consignmentNote", "Consignment exclusive release.");
        applyRatingScale(data);
        return data;
    }

    private List<Map<String, Object>> buildSampleProductImages(String productName) {
        List<Map<String, Object>> images = new ArrayList<>();
        images.add(createImage("https://placehold.co/960x960?text=" + urlEncode(productName), productName));
        images.add(createImage("https://placehold.co/960x960?text=Detail+View", productName + " detail"));
        return images;
    }

    private Map<String, Object> createImage(String url, String alt) {
        Map<String, Object> image = new HashMap<>();
        image.put("url", url);
        image.put("alt", alt);
        return image;
    }

    private List<Map<String, Object>> buildSampleVariantOptions() {
        List<Map<String, Object>> options = new ArrayList<>();

        Map<String, Object> colorOption = new LinkedHashMap<>();
        colorOption.put("name", "Color");
        colorOption.put("displayType", "color");
        colorOption.put("selectedValue", "Camel");
        colorOption.put("values",
                List.of(buildVariantValue("Camel", "#d6a977", true, SAMPLE_VARIANT_PRIMARY.toString()),
                        buildVariantValue("Midnight", "#111827", false, SAMPLE_VARIANT_SECONDARY.toString()),
                        buildVariantValue("Slate", "#6b7280", false, null)));
        options.add(colorOption);

        Map<String, Object> sizeOption = new LinkedHashMap<>();
        sizeOption.put("name", "Size");
        sizeOption.put("displayType", "button");
        sizeOption.put("selectedValue", "Large");
        sizeOption.put("values",
                List.of(buildVariantValue("Small", null, false, null), buildVariantValue("Medium", null, false, null),
                        buildVariantValue("Large", null, true, SAMPLE_VARIANT_PRIMARY.toString())));
        options.add(sizeOption);
        return options;
    }

    private Map<String, Object> buildVariantValue(String name, String colorCode, boolean selected, String variantId) {
        Map<String, Object> value = new HashMap<>();
        value.put("name", name);
        value.put("colorCode", colorCode);
        value.put("selected", selected);
        value.put("available", variantId != null);
        value.put("variantId", variantId);
        return value;
    }

    private List<Map<String, Object>> buildProductPageBreadcrumbs(Map<String, Object> productData) {
        List<Map<String, Object>> breadcrumbs = new ArrayList<>();
        Object categoryName = productData.get("categoryName");
        Object categorySlug = productData.get("categorySlug");
        if (categoryName != null && categorySlug != null) {
            Map<String, Object> categoryCrumb = new HashMap<>();
            categoryCrumb.put("name", categoryName);
            categoryCrumb.put("url", "/category/" + categorySlug);
            categoryCrumb.put("isLast", false);
            breadcrumbs.add(categoryCrumb);
        }

        Map<String, Object> productCrumb = new HashMap<>();
        productCrumb.put("name", productData.get("name"));
        productCrumb.put("url", null);
        productCrumb.put("isLast", true);
        breadcrumbs.add(productCrumb);
        return breadcrumbs;
    }

    private List<Map<String, Object>> buildSampleRelatedProducts() {
        Map<String, Object> tote = new HashMap<>();
        tote.put("id", SAMPLE_VARIANT_PRIMARY.toString());
        tote.put("slug", "artisan-tote");
        tote.put("name", "Artisan Tote");
        tote.put("imageUrl", "https://placehold.co/600x600?text=Tote");
        tote.put("price", "$58.00");
        tote.put("compareAtPrice", "$72.00");
        tote.put("categoryName", "Bags");
        tote.put("averageRating", 5);
        tote.put("reviewCount", 128);
        tote.put("onSale", true);
        tote.put("isNew", false);
        tote.put("inStock", true);
        tote.put("hasVariants", true);
        applyRatingScale(tote);

        Map<String, Object> scarf = new HashMap<>();
        scarf.put("id", SAMPLE_VARIANT_SECONDARY.toString());
        scarf.put("slug", "alpaca-scarf");
        scarf.put("name", "Alpaca Scarf");
        scarf.put("imageUrl", "https://placehold.co/600x600?text=Scarf");
        scarf.put("price", "$34.00");
        scarf.put("compareAtPrice", null);
        scarf.put("categoryName", "Accessories");
        scarf.put("averageRating", 4);
        scarf.put("reviewCount", 54);
        scarf.put("onSale", false);
        scarf.put("isNew", true);
        scarf.put("inStock", true);
        scarf.put("hasVariants", false);
        applyRatingScale(scarf);

        Map<String, Object> candles = new HashMap<>();
        candles.put("id", UUID.randomUUID().toString());
        candles.put("slug", "soy-candle-duo");
        candles.put("name", "Soy Candle Duo");
        candles.put("imageUrl", "https://placehold.co/600x600?text=Candles");
        candles.put("price", "$28.00");
        candles.put("compareAtPrice", "$32.00");
        candles.put("categoryName", "Home");
        candles.put("averageRating", 0);
        candles.put("reviewCount", 0);
        candles.put("onSale", true);
        candles.put("isNew", false);
        candles.put("inStock", true);
        candles.put("hasVariants", false);
        applyRatingScale(candles);

        return List.of(tote, scarf, candles);
    }

    private List<Map<String, Object>> buildSampleRecentOrders() {
        Map<String, Object> orderOne = new HashMap<>();
        orderOne.put("id", "1001");
        orderOne.put("number", "1001");
        orderOne.put("date", "Jan 09, 2026");
        orderOne.put("itemCount", 3);
        orderOne.put("total", "$178.90");
        orderOne.put("status", "Shipped");
        orderOne.put("statusColor", "emerald");

        Map<String, Object> orderTwo = new HashMap<>();
        orderTwo.put("id", "1000");
        orderTwo.put("number", "1000");
        orderTwo.put("date", "Dec 28, 2025");
        orderTwo.put("itemCount", 1);
        orderTwo.put("total", "$42.00");
        orderTwo.put("status", "Processing");
        orderTwo.put("statusColor", "amber");

        return List.of(orderOne, orderTwo);
    }

    private Map<String, Object> buildSampleDefaultAddress() {
        Map<String, Object> address = new HashMap<>();
        address.put("name", "Avery Lee");
        address.put("address1", "500 Village Drive");
        address.put("address2", "Suite 210");
        address.put("city", "Portland");
        address.put("state", "OR");
        address.put("postalCode", "97204");
        return address;
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) {
            return "$0.00";
        }
        return "$" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
