package villagecompute.storefront.api.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.Money;
import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.api.types.ProductDetail;
import villagecompute.storefront.api.types.ProductSummary;
import villagecompute.storefront.api.types.VariantMatrixEntry;
import villagecompute.storefront.api.types.VariantMatrixResponse;
import villagecompute.storefront.api.types.VariantOptionAxis;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.services.CatalogCacheService;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.CatalogService.CatalogSearchResult;
import villagecompute.storefront.services.FeatureToggle;
import villagecompute.storefront.services.mappers.ProductMapper;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.json.JsonObject;

/**
 * REST resource for public catalog browsing operations.
 *
 * <p>
 * Provides storefront endpoints for product catalog access. Supports both anonymous and authenticated requests. All
 * endpoints are tenant-scoped via {@link TenantContext}.
 *
 * <p>
 * <strong>Endpoints:</strong>
 * <ul>
 * <li>GET /api/v1/catalog/products - List/search products with pagination and filtering</li>
 * <li>GET /api/v1/catalog/products/{id} - Get product details by ID</li>
 * </ul>
 *
 * <p>
 * <strong>Caching:</strong> Search results are cached via {@link CatalogCacheService} with automatic invalidation on
 * catalog updates.
 *
 * <p>
 * <strong>Rate Limiting:</strong> 100 req/min (anonymous), 1000 req/min (authenticated)
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T3: Catalog API endpoints with tenant isolation</li>
 * <li>OpenAPI: /catalog/products paths</li>
 * <li>Architecture: Section 5 Contract Patterns (tenant-aware endpoints)</li>
 * </ul>
 */
@Path("/api/v1/catalog")
@Produces(MediaType.APPLICATION_JSON)
public class ProductResource {

    private static final Logger LOG = Logger.getLogger(ProductResource.class);
    private static final String FLAG_CATALOG_SEARCH = "catalog.search.enabled";
    private static final String FLAG_VARIANT_MATRIX = "catalog.variant-matrix.enabled";

    @Inject
    CatalogService catalogService;

    @Inject
    CatalogCacheService catalogCacheService;

    @Inject
    ProductMapper productMapper;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * List products with optional filtering, search, and pagination.
     *
     * @param category
     *            optional category slug filter
     * @param collection
     *            optional collection slug filter
     * @param search
     *            optional search query
     * @param page
     *            page number (1-indexed)
     * @param pageSize
     *            page size
     * @return paginated product list
     */
    @GET
    @Path("/products")
    public Response listProducts(@QueryParam("category") String category, @QueryParam("collection") String collection,
            @QueryParam("search") String search, @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        boolean searchProvided = search != null && !search.isBlank();

        // Validate pagination parameters
        int pageNumber = page != null && page > 0 ? page - 1 : 0; // Convert to 0-indexed
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;

        LOG.infof("GET /catalog/products - tenantId=%s, category=%s, collection=%s, search=%s, page=%d, size=%d",
                tenantId, category, collection, search, pageNumber, size);

        List<Product> products;
        long totalItems;

        if (searchProvided) {
            if (!featureToggle.isEnabled(FLAG_CATALOG_SEARCH)) {
                LOG.warnf("Catalog search disabled via feature flag - tenantId=%s", tenantId);
                return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Catalog Search Disabled",
                        "Search is not enabled for this tenant. Contact support to enable catalog.search.enabled.",
                        Status.FORBIDDEN)).build();
            }
            // Search with caching
            CatalogSearchResult result = catalogCacheService.getSearchResult(tenantId, search, pageNumber, size,
                    () -> catalogService.searchProducts(search, pageNumber, size));
            products = result.products();
            totalItems = result.totalItems();
            meterRegistry.counter("catalog.search", "tenant_id", tenantId.toString()).increment();
        } else {
            CatalogSearchResult result = catalogService.listProducts(category, collection, pageNumber, size);
            products = result.products();
            totalItems = result.totalItems();
            meterRegistry.counter("catalog.list", "tenant_id", tenantId.toString()).increment();
        }

        // Map to DTOs
        List<ProductSummary> productDtos = products.stream().map(productMapper::toSummary).toList();

        // Build paginated response
        PaginationMetadata pagination = new PaginationMetadata();
        pagination.setPage(pageNumber + 1); // Convert back to 1-indexed
        pagination.setPageSize(size);
        pagination.setTotalItems(totalItems);
        pagination.setTotalPages((int) Math.ceil((double) Math.max(totalItems, 0) / size));

        Map<String, Object> response = new HashMap<>();
        response.put("data", productDtos);
        response.put("pagination", pagination);

        return Response.ok(response).header("X-RateLimit-Limit", "100").header("X-RateLimit-Remaining", "99")
                .header("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 60)).build();
    }

    /**
     * Get product details by ID.
     *
     * @param productId
     *            product UUID
     * @return product details
     */
    @GET
    @Path("/products/{productId}")
    public Response getProduct(@PathParam("productId") UUID productId) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /catalog/products/%s - tenantId=%s", productId, tenantId);

        Optional<Product> productOpt = catalogService.getProduct(productId);

        if (productOpt.isEmpty()) {
            LOG.warnf("Product not found - tenantId=%s, productId=%s", tenantId, productId);
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        Product product = productOpt.get();
        ProductDetail productDto = productMapper.toDetail(product);

        meterRegistry.counter("catalog.get_product", "tenant_id", tenantId.toString()).increment();

        return Response.ok(productDto).header("X-RateLimit-Limit", "100").header("X-RateLimit-Remaining", "99")
                .header("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 60)).build();
    }

    /**
     * Get variant matrix for a product (option axes + variants).
     */
    @GET
    @Path("/products/{productId}/variant-matrix")
    public Response getVariantMatrix(@PathParam("productId") UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        if (!featureToggle.isEnabled(FLAG_VARIANT_MATRIX)) {
            LOG.warnf("Variant matrix disabled via feature flag - tenantId=%s", tenantId);
            return Response.status(Status.FORBIDDEN)
                    .entity(createProblemDetails("Variant Matrix Disabled",
                            "Variant matrix endpoint is gated by catalog.variant-matrix.enabled.", Status.FORBIDDEN))
                    .build();
        }

        LOG.infof("GET /catalog/products/%s/variant-matrix - tenantId=%s", productId, tenantId);

        Optional<Product> productOpt = catalogService.getProduct(productId);
        if (productOpt.isEmpty()) {
            LOG.warnf("Product not found for variant matrix - tenantId=%s, productId=%s", tenantId, productId);
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        List<ProductVariant> variants = catalogService.getProductVariants(productId);
        VariantMatrixResponse matrix = buildVariantMatrixResponse(productId, variants);

        meterRegistry.counter("catalog.variant_matrix", "tenant_id", tenantId.toString()).increment();

        return Response.ok(matrix).header("X-RateLimit-Limit", "100").header("X-RateLimit-Remaining", "99")
                .header("X-RateLimit-Reset", String.valueOf(System.currentTimeMillis() / 1000 + 60)).build();
    }

    /**
     * Create RFC 7807 Problem Details error response.
     *
     * @param title
     *            error title
     * @param detail
     *            error detail message
     * @param status
     *            HTTP status code
     * @return problem details object
     */
    private Map<String, Object> createProblemDetails(String title, String detail, Status status) {
        Map<String, Object> problem = new HashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.getStatusCode());
        if (detail != null && !detail.isBlank()) {
            problem.put("detail", detail);
        }
        return problem;
    }

    /**
     * Build variant matrix response from variant list.
     */
    private VariantMatrixResponse buildVariantMatrixResponse(UUID productId, List<ProductVariant> variants) {
        VariantMatrixResponse response = new VariantMatrixResponse();
        response.productId = productId;

        Map<String, Set<String>> axisValues = new LinkedHashMap<>();

        for (ProductVariant variant : variants) {
            Map<String, String> options = parseVariantOptions(variant.attributes);
            options.forEach((name, value) -> axisValues.computeIfAbsent(name, key -> new LinkedHashSet<>()).add(value));

            VariantMatrixEntry entry = new VariantMatrixEntry();
            entry.variantId = variant.id;
            entry.sku = variant.sku;
            entry.price = new Money(variant.price, "USD");
            entry.available = !"archived".equalsIgnoreCase(variant.status)
                    && !"deleted".equalsIgnoreCase(variant.status);
            entry.options = options;
            response.variants.add(entry);
        }

        axisValues.forEach((name, values) -> {
            VariantOptionAxis axis = new VariantOptionAxis();
            axis.name = name;
            axis.values = new ArrayList<>(values);
            response.optionAxes.add(axis);
        });

        return response;
    }

    private Map<String, String> parseVariantOptions(String attributesJson) {
        Map<String, String> options = new LinkedHashMap<>();
        if (attributesJson == null || attributesJson.isBlank()) {
            return options;
        }

        try {
            JsonObject json = new JsonObject(attributesJson);
            for (String field : json.fieldNames()) {
                Object value = json.getValue(field);
                options.put(field, value != null ? value.toString() : null);
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to parse variant attributes JSON");
        }
        return options;
    }
}
