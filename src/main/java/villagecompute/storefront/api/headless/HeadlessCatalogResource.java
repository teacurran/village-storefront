package villagecompute.storefront.api.headless;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.api.types.ProductDetail;
import villagecompute.storefront.api.types.ProductSummary;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.services.CatalogCacheService;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.CatalogService.CatalogSearchResult;
import villagecompute.storefront.services.FeatureToggle;
import villagecompute.storefront.services.mappers.ProductMapper;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Headless API resource for catalog operations.
 *
 * <p>
 * Provides OAuth-secured, read-only endpoints for product catalog access via headless/custom frontends. All endpoints
 * require valid OAuth client credentials with `catalog:read` scope.
 *
 * <p>
 * <strong>Endpoints:</strong>
 * <ul>
 * <li>GET /api/v1/headless/catalog/products - List products with pagination and search</li>
 * <li>GET /api/v1/headless/catalog/products/{id} - Get product details by ID</li>
 * </ul>
 *
 * <p>
 * <strong>Caching:</strong> Search results are cached via {@link CatalogCacheService} with automatic invalidation on
 * catalog updates.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Headless catalog endpoints with OAuth and caching</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * <li>Architecture: UI/UX Section 3.1.4 (Headless & Embedded Widgets)</li>
 * </ul>
 */
@Path("/api/v1/headless/catalog")
@Produces(MediaType.APPLICATION_JSON)
@HeadlessApiBinding
public class HeadlessCatalogResource {

    private static final Logger LOG = Logger.getLogger(HeadlessCatalogResource.class);

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
     * List products with optional search and pagination.
     *
     * @param search
     *            optional search query
     * @param page
     *            page number (1-indexed)
     * @param pageSize
     *            page size
     * @param requestContext
     *            JAX-RS request context
     * @return paginated product list
     */
    @GET
    @Path("/products")
    public Response listProducts(@QueryParam("search") String search, @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        // Note: OAuth client is available in request context (set by HeadlessAuthFilter)
        // For observability, we can retrieve it via: (OAuthClient) requestContext.getProperty("oauthClient")
        UUID tenantId = TenantContext.getCurrentTenantId();

        // Validate pagination parameters
        int pageNumber = page != null && page > 0 ? page - 1 : 0; // Convert to 0-indexed
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;

        LOG.infof("GET /headless/catalog/products - tenantId=%s, search=%s, page=%d, size=%d", tenantId, search,
                pageNumber, size);

        List<Product> products;
        long totalItems;

        if (search != null && !search.isBlank()) {
            // Search with caching
            CatalogSearchResult result = catalogCacheService.getSearchResult(tenantId, search, pageNumber, size,
                    () -> catalogService.searchProducts(search, pageNumber, size));
            products = result.products();
            totalItems = result.totalItems();
            meterRegistry.counter("headless.catalog.search", "tenant_id", tenantId.toString()).increment();
        } else {
            // List active products
            products = catalogService.listActiveProducts(pageNumber, size);
            totalItems = catalogService.countActiveProducts();
            meterRegistry.counter("headless.catalog.list", "tenant_id", tenantId.toString()).increment();
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
        response.put("products", productDtos);
        response.put("pagination", pagination);

        return Response.ok(response).build();
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

        // Verify feature flag
        if (!featureToggle.isEnabled("headless.api.enabled")) {
            LOG.warn("Headless API feature disabled");
            return Response.status(Status.FORBIDDEN).entity(createProblemDetails("Feature Disabled",
                    "Headless API is not enabled for this tenant", Status.FORBIDDEN)).build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /headless/catalog/products/%s - tenantId=%s", productId, tenantId);

        Optional<Product> productOpt = catalogService.getProduct(productId);

        if (productOpt.isEmpty()) {
            LOG.warnf("Product not found - tenantId=%s, productId=%s", tenantId, productId);
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        Product product = productOpt.get();
        ProductDetail productDto = productMapper.toDetail(product);

        meterRegistry.counter("headless.catalog.get_product", "tenant_id", tenantId.toString()).increment();

        return Response.ok(productDto).build();
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
}
