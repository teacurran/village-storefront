package villagecompute.storefront.api.rest;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.UriInfo;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.CategoryDto;
import villagecompute.storefront.api.types.CollectionDto;
import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.api.types.ProductDto;
import villagecompute.storefront.api.types.ProductVariantDto;
import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.CategoryRepository;
import villagecompute.storefront.data.repositories.CollectionRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.services.CatalogJobService;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.services.ValidationException;
import villagecompute.storefront.services.mappers.CategoryMapper;
import villagecompute.storefront.services.mappers.CollectionMapper;
import villagecompute.storefront.services.mappers.ProductMapper;
import villagecompute.storefront.services.mappers.ProductVariantMapper;
import villagecompute.storefront.services.metrics.CatalogMetrics;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.annotation.Timed;
import io.opentelemetry.api.trace.Span;

/**
 * REST resource for admin catalog management operations.
 *
 * <p>
 * Provides endpoints for category and collection CRUD operations:
 * <ul>
 * <li>GET /api/v1/admin/catalog/categories - List categories</li>
 * <li>POST /api/v1/admin/catalog/categories - Create category</li>
 * <li>GET /api/v1/admin/catalog/categories/{id} - Get category details</li>
 * <li>PUT /api/v1/admin/catalog/categories/{id} - Update category</li>
 * <li>DELETE /api/v1/admin/catalog/categories/{id} - Delete category</li>
 * <li>GET /api/v1/admin/catalog/collections - List collections</li>
 * <li>POST /api/v1/admin/catalog/collections - Create collection</li>
 * <li>GET /api/v1/admin/catalog/collections/{id} - Get collection details</li>
 * <li>PUT /api/v1/admin/catalog/collections/{id} - Update collection</li>
 * <li>DELETE /api/v1/admin/catalog/collections/{id} - Delete collection</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext and require admin authentication.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T3: Catalog API endpoints with tenant isolation</li>
 * <li>OpenAPI: /admin/catalog/** paths</li>
 * <li>Architecture: Section 4 Catalog Domain Module</li>
 * </ul>
 */
@Path("/api/v1/admin/catalog")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CatalogAdminResource {

    private static final Logger LOG = Logger.getLogger(CatalogAdminResource.class);

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    CollectionRepository collectionRepository;

    @Inject
    CategoryMapper categoryMapper;

    @Inject
    CollectionMapper collectionMapper;

    @Inject
    CatalogJobService catalogJobService;

    @Inject
    CatalogMetrics catalogMetrics;

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    ProductMapper productMapper;

    @Inject
    ProductVariantMapper variantMapper;

    @Inject
    CatalogService catalogService;

    @Context
    UriInfo uriInfo;

    // ========================================
    // Product Endpoints
    // ========================================

    /**
     * List all products with optional pagination and filtering.
     *
     * @param status
     *            optional status filter
     * @param page
     *            page number (1-indexed)
     * @param pageSize
     *            page size
     * @return paginated list of products
     */
    @GET
    @Path("/products")
    @Timed(
            value = "catalog.admin.product.list",
            description = "Time to list products")
    public Response listProducts(@QueryParam("status") String status, @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/products - tenantId=%s, status=%s, page=%d, pageSize=%d", tenantId, status, page,
                pageSize);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "list_products");
        if (status != null && !status.isBlank()) {
            span.setAttribute("product.status_filter", status);
        }

        // Validate pagination parameters
        int pageNumber = page != null && page > 0 ? page - 1 : 0; // Convert to 0-indexed
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;

        List<Product> products;
        long totalItems;

        if (status != null && !status.isBlank()) {
            products = productRepository.findByStatusForCurrentTenant(status, pageNumber, size);
            totalItems = productRepository.countByStatusForCurrentTenant(status);
        } else {
            products = productRepository.findByCurrentTenant(pageNumber, size);
            totalItems = productRepository.countByCurrentTenant();
        }

        List<ProductDto> dtos = products.stream().map(productMapper::toDto).collect(Collectors.toList());

        // Build paginated response
        PaginationMetadata pagination = new PaginationMetadata();
        pagination.setPage(pageNumber + 1); // Convert back to 1-indexed
        pagination.setPageSize(size);
        pagination.setTotalItems(totalItems);
        pagination.setTotalPages((int) Math.ceil((double) Math.max(totalItems, 0) / size));

        Map<String, Object> response = new HashMap<>();
        response.put("data", dtos);
        response.put("pagination", pagination);
        response.put("links", buildPaginationLinks(pageNumber + 1, size, pagination.getTotalPages()));

        return Response.ok(response).build();
    }

    /**
     * Create a new product.
     *
     * @param dto
     *            product creation request
     * @return created product
     */
    @POST
    @Path("/products")
    @Transactional
    @Timed(
            value = "catalog.admin.product.create",
            description = "Time to create product")
    public Response createProduct(@Valid ProductDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/products - tenantId=%s, slug=%s", tenantId, dto.slug);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "create_product");
        span.setAttribute("product.slug", dto.slug);

        // Check for duplicate slug
        if (productRepository.findBySlug(dto.slug).isPresent()) {
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Duplicate Product Slug",
                    "Product with slug '" + dto.slug + "' already exists", Status.CONFLICT)).build();
        }

        Product product = productMapper.toEntity(dto);

        try {
            Product created = catalogService.createProduct(product);
            ProductDto responseDto = productMapper.toDto(created);
            return Response.status(Status.CREATED).entity(responseDto).build();
        } catch (ValidationException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Product Data", e.getMessage(), Status.BAD_REQUEST)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Product Data", e.getMessage(), Status.BAD_REQUEST)).build();
        }
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
    @Timed(
            value = "catalog.admin.product.get",
            description = "Time to fetch product details")
    public Response getProduct(@PathParam("productId") UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/products/%s - tenantId=%s", productId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "get_product");
        span.setAttribute("product.id", productId.toString());

        Optional<Product> productOpt = catalogService.getProduct(productId);

        if (productOpt.isEmpty()) {
            LOG.warnf("Product not found - tenantId=%s, productId=%s", tenantId, productId);
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        Product product = productOpt.get();
        ProductDto dto = productMapper.toDto(product);

        return Response.ok(dto).build();
    }

    /**
     * Update product by ID.
     *
     * @param productId
     *            product UUID
     * @param dto
     *            updated product data
     * @return updated product
     */
    @PUT
    @Path("/products/{productId}")
    @Transactional
    @Timed(
            value = "catalog.admin.product.update",
            description = "Time to update product")
    public Response updateProduct(@PathParam("productId") UUID productId, @Valid ProductDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/catalog/products/%s - tenantId=%s", productId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "update_product");
        span.setAttribute("product.id", productId.toString());

        try {
            Optional<Product> productOpt = catalogService.getProduct(productId);

            if (productOpt.isEmpty()) {
                return Response.status(Status.NOT_FOUND).entity(
                        createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                        .build();
            }

            Product product = productOpt.get();
            productMapper.updateEntityFromDto(dto, product);
            Product updated = catalogService.updateProduct(productId, product);

            ProductDto responseDto = productMapper.toDto(updated);
            return Response.ok(responseDto).build();

        } catch (ValidationException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Product Data", e.getMessage(), Status.BAD_REQUEST)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Product Data", e.getMessage(), Status.BAD_REQUEST)).build();
        }
    }

    /**
     * Delete product by ID (soft delete by setting status to 'deleted').
     *
     * @param productId
     *            product UUID
     * @return no content
     */
    @DELETE
    @Path("/products/{productId}")
    @Transactional
    @Timed(
            value = "catalog.admin.product.delete",
            description = "Time to delete product")
    public Response deleteProduct(@PathParam("productId") UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/catalog/products/%s - tenantId=%s", productId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "delete_product");
        span.setAttribute("product.id", productId.toString());

        try {
            catalogService.deleteProduct(productId);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Product Not Found", e.getMessage(), Status.NOT_FOUND)).build();
        }
    }

    // ========================================
    // Variant Endpoints
    // ========================================

    /**
     * List all variants for a product.
     *
     * @param productId
     *            product UUID
     * @return list of variants
     */
    @GET
    @Path("/products/{productId}/variants")
    @Timed(
            value = "catalog.admin.variant.list",
            description = "Time to list product variants")
    public Response listVariants(@PathParam("productId") UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/products/%s/variants - tenantId=%s", productId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "list_variants");
        span.setAttribute("product.id", productId.toString());

        // Verify product exists and belongs to tenant
        Optional<Product> productOpt = catalogService.getProduct(productId);
        if (productOpt.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        List<ProductVariant> variants = catalogService.getProductVariants(productId);
        List<ProductVariantDto> dtos = variants.stream().map(variantMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * Create a new variant for a product.
     *
     * @param productId
     *            product UUID
     * @param dto
     *            variant creation request
     * @return created variant
     */
    @POST
    @Path("/products/{productId}/variants")
    @Transactional
    @Timed(
            value = "catalog.admin.variant.create",
            description = "Time to create product variant")
    public Response createVariant(@PathParam("productId") UUID productId, @Valid ProductVariantDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/products/%s/variants - tenantId=%s, sku=%s", productId, tenantId, dto.sku);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "create_variant");
        span.setAttribute("product.id", productId.toString());
        span.setAttribute("variant.sku", dto.sku);

        // Verify product exists and belongs to tenant
        Optional<Product> productOpt = catalogService.getProduct(productId);
        if (productOpt.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        // Check for duplicate SKU
        if (variantRepository.findBySku(dto.sku).isPresent()) {
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Duplicate Variant SKU",
                    "Variant with SKU '" + dto.sku + "' already exists", Status.CONFLICT)).build();
        }

        ProductVariant variant = variantMapper.toEntity(dto);
        variant.product = productOpt.get();

        try {
            ProductVariant created = catalogService.createVariant(variant);
            ProductVariantDto responseDto = variantMapper.toDto(created);

            return Response.status(Status.CREATED).entity(responseDto).build();
        } catch (ValidationException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Variant Data", e.getMessage(), Status.BAD_REQUEST)).build();
        }
    }

    /**
     * Get variant details by ID.
     *
     * @param productId
     *            product UUID
     * @param variantId
     *            variant UUID
     * @return variant details
     */
    @GET
    @Path("/products/{productId}/variants/{variantId}")
    @Timed(
            value = "catalog.admin.variant.get",
            description = "Time to fetch variant details")
    public Response getVariant(@PathParam("productId") UUID productId, @PathParam("variantId") UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/products/%s/variants/%s - tenantId=%s", productId, variantId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "get_variant");
        span.setAttribute("product.id", productId.toString());
        span.setAttribute("variant.id", variantId.toString());

        // Verify product exists and belongs to tenant
        Optional<Product> productOpt = catalogService.getProduct(productId);
        if (productOpt.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                    .build();
        }

        Optional<ProductVariant> variantOpt = findVariantForProduct(productId, variantId);

        if (variantOpt.isEmpty()) {
            LOG.warnf("Variant not found - tenantId=%s, productId=%s, variantId=%s", tenantId, productId, variantId);
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Variant Not Found", "Variant not found: " + variantId, Status.NOT_FOUND))
                    .build();
        }

        ProductVariant variant = variantOpt.get();
        ProductVariantDto dto = variantMapper.toDto(variant);

        return Response.ok(dto).build();
    }

    /**
     * Update variant by ID.
     *
     * @param productId
     *            product UUID
     * @param variantId
     *            variant UUID
     * @param dto
     *            updated variant data
     * @return updated variant
     */
    @PUT
    @Path("/products/{productId}/variants/{variantId}")
    @Transactional
    @Timed(
            value = "catalog.admin.variant.update",
            description = "Time to update variant")
    public Response updateVariant(@PathParam("productId") UUID productId, @PathParam("variantId") UUID variantId,
            @Valid ProductVariantDto dto) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/catalog/products/%s/variants/%s - tenantId=%s", productId, variantId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "update_variant");
        span.setAttribute("product.id", productId.toString());
        span.setAttribute("variant.id", variantId.toString());

        try {
            // Verify product exists and belongs to tenant
            Optional<Product> productOpt = catalogService.getProduct(productId);
            if (productOpt.isEmpty()) {
                return Response.status(Status.NOT_FOUND).entity(
                        createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                        .build();
            }

            Optional<ProductVariant> variantOpt = findVariantForProduct(productId, variantId);
            if (variantOpt.isEmpty()) {
                return Response.status(Status.NOT_FOUND).entity(
                        createProblemDetails("Variant Not Found", "Variant not found: " + variantId, Status.NOT_FOUND))
                        .build();
            }

            ProductVariant updated = catalogService.updateVariant(variantId, dto);
            ProductVariantDto responseDto = variantMapper.toDto(updated);

            return Response.ok(responseDto).build();

        } catch (ValidationException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Variant Data", e.getMessage(), Status.BAD_REQUEST)).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Variant Not Found", e.getMessage(), Status.NOT_FOUND)).build();
        }
    }

    /**
     * Delete variant by ID (soft delete by setting status to 'deleted').
     *
     * @param productId
     *            product UUID
     * @param variantId
     *            variant UUID
     * @return no content
     */
    @DELETE
    @Path("/products/{productId}/variants/{variantId}")
    @Transactional
    @Timed(
            value = "catalog.admin.variant.delete",
            description = "Time to delete variant")
    public Response deleteVariant(@PathParam("productId") UUID productId, @PathParam("variantId") UUID variantId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/catalog/products/%s/variants/%s - tenantId=%s", productId, variantId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "delete_variant");
        span.setAttribute("product.id", productId.toString());
        span.setAttribute("variant.id", variantId.toString());

        try {
            // Verify product exists and belongs to tenant
            Optional<Product> productOpt = catalogService.getProduct(productId);
            if (productOpt.isEmpty()) {
                return Response.status(Status.NOT_FOUND).entity(
                        createProblemDetails("Product Not Found", "Product not found: " + productId, Status.NOT_FOUND))
                        .build();
            }

            Optional<ProductVariant> variantOpt = findVariantForProduct(productId, variantId);
            if (variantOpt.isEmpty()) {
                return Response.status(Status.NOT_FOUND).entity(
                        createProblemDetails("Variant Not Found", "Variant not found: " + variantId, Status.NOT_FOUND))
                        .build();
            }

            catalogService.deleteVariant(variantId);
            return Response.noContent().build();

        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Variant Not Found", e.getMessage(), Status.NOT_FOUND)).build();
        }
    }

    // ========================================
    // Category Endpoints
    // ========================================

    /**
     * List all categories.
     *
     * @return list of categories
     */
    @GET
    @Path("/categories")
    @Timed(
            value = "catalog.admin.category.list",
            description = "Time to list categories")
    public Response listCategories() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/categories - tenantId=%s", tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "list_categories");

        List<Category> categories = categoryRepository.findByCurrentTenant();
        List<CategoryDto> dtos = categories.stream().map(categoryMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * Create a new category.
     *
     * @param dto
     *            category creation request
     * @return created category
     */
    @POST
    @Path("/categories")
    @Transactional
    @Timed(
            value = "catalog.admin.category.create",
            description = "Time to create category")
    public Response createCategory(@Valid CategoryDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/categories - tenantId=%s, code=%s", tenantId, dto.code);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "create_category");
        span.setAttribute("category.code", dto.code);

        // Check for duplicate code
        if (categoryRepository.findByCode(dto.code).isPresent()) {
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Duplicate Category Code",
                    "Category with code '" + dto.code + "' already exists", Status.CONFLICT)).build();
        }

        Category parentCategory = resolveParentCategory(dto.parentId, tenantId);
        if (dto.parentId != null && parentCategory == null) {
            return Response.status(Status.BAD_REQUEST).entity(createProblemDetails("Invalid Parent Category",
                    "Parent category not found: " + dto.parentId, Status.BAD_REQUEST)).build();
        }

        Category category = categoryMapper.toEntity(dto);
        category.parent = parentCategory;
        categoryRepository.persist(category);

        catalogMetrics.recordCategoryCreated(tenantId);

        CategoryDto responseDto = categoryMapper.toDto(category);
        return Response.status(Status.CREATED).entity(responseDto).build();
    }

    /**
     * Get category details by ID.
     *
     * @param categoryId
     *            category UUID
     * @return category details
     */
    @GET
    @Path("/categories/{categoryId}")
    public Response getCategory(@PathParam("categoryId") UUID categoryId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/categories/%s - tenantId=%s", categoryId, tenantId);

        Optional<Category> categoryOpt = categoryRepository.findByIdOptional(categoryId);

        if (categoryOpt.isEmpty() || !categoryOpt.get().tenant.id.equals(tenantId)) {
            LOG.warnf("Category not found - tenantId=%s, categoryId=%s", tenantId, categoryId);
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Category Not Found", "Category not found: " + categoryId, Status.NOT_FOUND))
                    .build();
        }

        Category category = categoryOpt.get();
        CategoryDto dto = categoryMapper.toDto(category);

        return Response.ok(dto).build();
    }

    /**
     * Update category by ID.
     *
     * @param categoryId
     *            category UUID
     * @param dto
     *            updated category data
     * @return updated category
     */
    @PUT
    @Path("/categories/{categoryId}")
    @Transactional
    @Timed(
            value = "catalog.admin.category.update",
            description = "Time to update category")
    public Response updateCategory(@PathParam("categoryId") UUID categoryId, @Valid CategoryDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/catalog/categories/%s - tenantId=%s", categoryId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "update_category");
        span.setAttribute("category.id", categoryId.toString());

        Optional<Category> categoryOpt = categoryRepository.findByIdOptional(categoryId);

        if (categoryOpt.isEmpty() || !categoryOpt.get().tenant.id.equals(tenantId)) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Category Not Found", "Category not found: " + categoryId, Status.NOT_FOUND))
                    .build();
        }

        Category category = categoryOpt.get();

        // Update fields
        category.code = dto.code;
        category.name = dto.name;
        category.slug = dto.slug;
        category.description = dto.description;
        category.displayOrder = dto.displayOrder;
        category.status = dto.status;
        if (dto.parentId != null && dto.parentId.equals(categoryId)) {
            return Response.status(Status.BAD_REQUEST).entity(createProblemDetails("Invalid Parent Category",
                    "Category cannot be its own parent", Status.BAD_REQUEST)).build();
        }
        Category parentCategory = resolveParentCategory(dto.parentId, tenantId);
        if (dto.parentId != null && parentCategory == null) {
            return Response.status(Status.BAD_REQUEST).entity(createProblemDetails("Invalid Parent Category",
                    "Parent category not found: " + dto.parentId, Status.BAD_REQUEST)).build();
        }
        category.parent = parentCategory;

        categoryRepository.persist(category);
        catalogMetrics.recordCategoryUpdated(tenantId);

        CategoryDto responseDto = categoryMapper.toDto(category);
        return Response.ok(responseDto).build();
    }

    /**
     * Delete category by ID (soft delete by setting status to 'deleted').
     *
     * @param categoryId
     *            category UUID
     * @return no content
     */
    @DELETE
    @Path("/categories/{categoryId}")
    @Transactional
    @Timed(
            value = "catalog.admin.category.delete",
            description = "Time to delete category")
    public Response deleteCategory(@PathParam("categoryId") UUID categoryId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/catalog/categories/%s - tenantId=%s", categoryId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "delete_category");
        span.setAttribute("category.id", categoryId.toString());

        Optional<Category> categoryOpt = categoryRepository.findByIdOptional(categoryId);

        if (categoryOpt.isEmpty() || !categoryOpt.get().tenant.id.equals(tenantId)) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Category Not Found", "Category not found: " + categoryId, Status.NOT_FOUND))
                    .build();
        }

        Category category = categoryOpt.get();
        category.status = "deleted";
        categoryRepository.persist(category);
        catalogMetrics.recordCategoryDeleted(tenantId);

        return Response.noContent().build();
    }

    // ========================================
    // Collection Endpoints
    // ========================================

    /**
     * List collections with optional pagination and filtering.
     *
     * @param status
     *            optional status filter
     * @param page
     *            page number (1-indexed)
     * @param pageSize
     *            page size
     * @return paginated list of collections
     */
    @GET
    @Path("/collections")
    @Timed(
            value = "catalog.admin.collection.list",
            description = "Time to list collections")
    public Response listCollections(@QueryParam("status") String status, @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/collections - tenantId=%s, status=%s, page=%d, pageSize=%d", tenantId, status,
                page, pageSize);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "list_collections");
        if (status != null && !status.isBlank()) {
            span.setAttribute("collection.status_filter", status);
        }

        // Validate pagination parameters
        int pageNumber = page != null && page > 0 ? page - 1 : 0; // Convert to 0-indexed
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;

        List<Collection> collections;
        long totalItems;

        if (status != null && !status.isBlank()) {
            collections = collectionRepository.findByStatus(status, pageNumber, size);
            totalItems = collectionRepository.countByStatus(status);
        } else {
            collections = collectionRepository.findByCurrentTenant(pageNumber, size);
            totalItems = collectionRepository.countByCurrentTenant();
        }

        List<CollectionDto> dtos = collections.stream().map(collectionMapper::toDto).collect(Collectors.toList());

        // Build paginated response
        PaginationMetadata pagination = new PaginationMetadata();
        pagination.setPage(pageNumber + 1); // Convert back to 1-indexed
        pagination.setPageSize(size);
        pagination.setTotalItems(totalItems);
        pagination.setTotalPages((int) Math.ceil((double) Math.max(totalItems, 0) / size));

        Map<String, Object> response = new HashMap<>();
        response.put("data", dtos);
        response.put("pagination", pagination);
        response.put("links", buildPaginationLinks(pageNumber + 1, size, pagination.getTotalPages()));

        return Response.ok(response).build();
    }

    /**
     * Create a new collection.
     *
     * @param dto
     *            collection creation request
     * @return created collection
     */
    @POST
    @Path("/collections")
    @Transactional
    @Timed(
            value = "catalog.admin.collection.create",
            description = "Time to create collection")
    public Response createCollection(@Valid CollectionDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/collections - tenantId=%s, code=%s", tenantId, dto.code);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "create_collection");
        span.setAttribute("collection.code", dto.code);

        // Check for duplicate code
        if (collectionRepository.findByCode(dto.code).isPresent()) {
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Duplicate Collection Code",
                    "Collection with code '" + dto.code + "' already exists", Status.CONFLICT)).build();
        }

        Collection collection = collectionMapper.toEntity(dto);
        collectionRepository.persist(collection);
        catalogMetrics.recordCollectionCreated(tenantId);

        CollectionDto responseDto = collectionMapper.toDto(collection);
        return Response.status(Status.CREATED).entity(responseDto).build();
    }

    /**
     * Get collection details by ID.
     *
     * @param collectionId
     *            collection UUID
     * @return collection details
     */
    @GET
    @Path("/collections/{collectionId}")
    @Timed(
            value = "catalog.admin.collection.get",
            description = "Time to fetch collection details")
    public Response getCollection(@PathParam("collectionId") UUID collectionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/collections/%s - tenantId=%s", collectionId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "get_collection");
        span.setAttribute("collection.id", collectionId.toString());

        Optional<Collection> collectionOpt = collectionRepository.findByIdAndTenant(collectionId);

        if (collectionOpt.isEmpty()) {
            LOG.warnf("Collection not found - tenantId=%s, collectionId=%s", tenantId, collectionId);
            return Response.status(Status.NOT_FOUND).entity(createProblemDetails("Collection Not Found",
                    "Collection not found: " + collectionId, Status.NOT_FOUND)).build();
        }

        Collection collection = collectionOpt.get();
        CollectionDto dto = collectionMapper.toDto(collection);

        return Response.ok(dto).build();
    }

    /**
     * Update collection by ID.
     *
     * @param collectionId
     *            collection UUID
     * @param dto
     *            updated collection data
     * @return updated collection
     */
    @PUT
    @Path("/collections/{collectionId}")
    @Transactional
    @Timed(
            value = "catalog.admin.collection.update",
            description = "Time to update collection")
    public Response updateCollection(@PathParam("collectionId") UUID collectionId, @Valid CollectionDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/catalog/collections/%s - tenantId=%s", collectionId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "update_collection");
        span.setAttribute("collection.id", collectionId.toString());

        Optional<Collection> collectionOpt = collectionRepository.findByIdAndTenant(collectionId);

        if (collectionOpt.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(createProblemDetails("Collection Not Found",
                    "Collection not found: " + collectionId, Status.NOT_FOUND)).build();
        }

        Collection collection = collectionOpt.get();

        // Update fields
        collection.code = dto.code;
        collection.name = dto.name;
        collection.slug = dto.slug;
        collection.description = dto.description;
        collection.imageUrl = dto.imageUrl;
        collection.displayOrder = dto.displayOrder;
        collection.collectionType = dto.collectionType;
        collection.selectionRules = dto.selectionRules;
        collection.published = dto.published;
        collection.publishedAt = dto.publishedAt;
        collection.status = dto.status;
        collection.seoTitle = dto.seoTitle;
        collection.seoDescription = dto.seoDescription;

        collectionRepository.persist(collection);
        catalogMetrics.recordCollectionUpdated(tenantId);

        CollectionDto responseDto = collectionMapper.toDto(collection);
        return Response.ok(responseDto).build();
    }

    /**
     * Delete collection by ID (soft delete by setting status to 'deleted').
     *
     * @param collectionId
     *            collection UUID
     * @return no content
     */
    @DELETE
    @Path("/collections/{collectionId}")
    @Transactional
    @Timed(
            value = "catalog.admin.collection.delete",
            description = "Time to delete collection")
    public Response deleteCollection(@PathParam("collectionId") UUID collectionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/catalog/collections/%s - tenantId=%s", collectionId, tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "delete_collection");
        span.setAttribute("collection.id", collectionId.toString());

        Optional<Collection> collectionOpt = collectionRepository.findByIdAndTenant(collectionId);

        if (collectionOpt.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(createProblemDetails("Collection Not Found",
                    "Collection not found: " + collectionId, Status.NOT_FOUND)).build();
        }

        Collection collection = collectionOpt.get();
        collection.status = "deleted";
        collectionRepository.persist(collection);
        catalogMetrics.recordCollectionDeleted(tenantId);

        return Response.noContent().build();
    }

    // ========================================
    // Import/Export Endpoints
    // ========================================

    /**
     * Enqueue a catalog import job.
     *
     * @param request
     *            import request containing file location and options
     * @return job ID
     */
    @POST
    @Path("/import")
    @Timed(
            value = "catalog.admin.import.enqueue",
            description = "Time to enqueue catalog import job")
    public Response importCatalog(Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/import - tenantId=%s", tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "import_enqueue");

        // Extract request parameters
        String fileLocation = (String) request.get("fileLocation");
        String requestedBy = (String) request.getOrDefault("requestedBy", "admin");

        @SuppressWarnings("unchecked")
        Map<String, String> options = (Map<String, String>) request.getOrDefault("options", new HashMap<>());

        if (fileLocation == null || fileLocation.isBlank()) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Request", "fileLocation is required", Status.BAD_REQUEST))
                    .build();
        }

        span.setAttribute("import.file_location", fileLocation);
        span.setAttribute("import.requested_by", requestedBy);

        try {
            UUID jobId = catalogJobService.enqueueImport(fileLocation, requestedBy, options);
            catalogMetrics.recordImportEnqueued(tenantId);

            Map<String, Object> response = new HashMap<>();
            response.put("jobId", jobId.toString());
            response.put("status", "enqueued");
            response.put("message", "Catalog import job enqueued successfully");

            span.setAttribute("import.job_id", jobId.toString());

            return Response.accepted().entity(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Import request rejected - tenantId=%s, error=%s", tenantId, e.getMessage());
            catalogMetrics.recordImportFailure(tenantId, "validation_error");
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Request", e.getMessage(), Status.BAD_REQUEST)).build();
        } catch (IllegalStateException e) {
            LOG.warnf("Import request rejected - tenantId=%s, error=%s", tenantId, e.getMessage());
            catalogMetrics.recordImportFailure(tenantId, "service_unavailable");
            return Response.status(Status.SERVICE_UNAVAILABLE)
                    .entity(createProblemDetails("Import Unavailable", e.getMessage(), Status.SERVICE_UNAVAILABLE))
                    .build();
        }
    }

    /**
     * Enqueue a catalog export job.
     *
     * @param request
     *            export request containing filters and format
     * @return job ID
     */
    @POST
    @Path("/export")
    @Timed(
            value = "catalog.admin.export.enqueue",
            description = "Time to enqueue catalog export job")
    public Response exportCatalog(Map<String, Object> request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/export - tenantId=%s", tenantId);

        Span span = spanWithTenant(tenantId);
        span.setAttribute("catalog.operation", "export_enqueue");

        String requestedBy = (String) request.getOrDefault("requestedBy", "admin");
        String format = (String) request.getOrDefault("format", "csv");

        @SuppressWarnings("unchecked")
        Map<String, String> filters = (Map<String, String>) request.getOrDefault("filters", new HashMap<>());

        span.setAttribute("export.format", format);
        span.setAttribute("export.requested_by", requestedBy);

        try {
            UUID jobId = catalogJobService.enqueueExport(requestedBy, filters, format);
            catalogMetrics.recordExportEnqueued(tenantId);

            Map<String, Object> response = new HashMap<>();
            response.put("jobId", jobId.toString());
            response.put("status", "enqueued");
            response.put("message", "Catalog export job enqueued successfully");

            span.setAttribute("export.job_id", jobId.toString());

            return Response.accepted().entity(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Export request rejected - tenantId=%s, error=%s", tenantId, e.getMessage());
            catalogMetrics.recordExportFailure(tenantId, "validation_error");
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Invalid Request", e.getMessage(), Status.BAD_REQUEST)).build();
        } catch (IllegalStateException e) {
            LOG.warnf("Export request rejected - tenantId=%s, error=%s", tenantId, e.getMessage());
            catalogMetrics.recordExportFailure(tenantId, "service_unavailable");
            return Response.status(Status.SERVICE_UNAVAILABLE)
                    .entity(createProblemDetails("Export Unavailable", e.getMessage(), Status.SERVICE_UNAVAILABLE))
                    .build();
        }
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
        String message = (detail != null && !detail.isBlank()) ? detail : title;
        problem.put("message", message);
        if (detail != null && !detail.isBlank()) {
            problem.put("detail", detail);
        }
        if (TenantContext.hasContext()) {
            problem.put("tenantId", TenantContext.getCurrentTenantId().toString());
        }
        Span span = Span.current();
        String traceId = span.getSpanContext().isValid() ? span.getSpanContext().getTraceId()
                : UUID.randomUUID().toString().replace("-", "");
        problem.put("traceId", traceId);
        problem.put("timestamp", OffsetDateTime.now().toString());
        if (uriInfo != null) {
            problem.put("instance", uriInfo.getRequestUri().toString());
        }
        return problem;
    }

    private Span spanWithTenant(UUID tenantId) {
        Span span = Span.current();
        if (tenantId != null && span.getSpanContext().isValid()) {
            String tenantValue = tenantId.toString();
            span.setAttribute("tenant.id", tenantValue);
            span.setAttribute("tenant_id", tenantValue);
        }
        return span;
    }

    private Category resolveParentCategory(UUID parentId, UUID tenantId) {
        if (parentId == null) {
            return null;
        }

        Optional<Category> parentOpt = categoryRepository.findByIdOptional(parentId);
        if (parentOpt.isEmpty()) {
            return null;
        }

        Category parentCategory = parentOpt.get();
        return parentCategory.tenant.id.equals(tenantId) ? parentCategory : null;
    }

    private Optional<ProductVariant> findVariantForProduct(UUID productId, UUID variantId) {
        return variantRepository.findByIdForTenant(variantId)
                .filter(variant -> variant.product != null && variant.product.id.equals(productId));
    }

    private Map<String, String> buildPaginationLinks(int page, int size, Integer totalPages) {
        Map<String, String> links = new HashMap<>();
        if (uriInfo == null) {
            return links;
        }
        links.put("self", buildPageLink(page, size));
        int resolvedTotal = (totalPages == null || totalPages < 1) ? 1 : totalPages;
        if (page > 1) {
            links.put("prev", buildPageLink(page - 1, size));
        }
        if (page < resolvedTotal) {
            links.put("next", buildPageLink(page + 1, size));
        }
        return links;
    }

    private String buildPageLink(int page, int size) {
        return uriInfo.getRequestUriBuilder().replaceQueryParam("page", page).replaceQueryParam("pageSize", size)
                .build().toString();
    }
}
