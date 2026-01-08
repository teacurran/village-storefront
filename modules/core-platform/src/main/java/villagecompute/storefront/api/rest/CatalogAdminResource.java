package villagecompute.storefront.api.rest;

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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.CategoryDto;
import villagecompute.storefront.api.types.CollectionDto;
import villagecompute.storefront.api.types.PaginationMetadata;
import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.repositories.CategoryRepository;
import villagecompute.storefront.data.repositories.CollectionRepository;
import villagecompute.storefront.services.mappers.CategoryMapper;
import villagecompute.storefront.services.mappers.CollectionMapper;
import villagecompute.storefront.tenant.TenantContext;

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
    public Response listCategories() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/categories - tenantId=%s", tenantId);

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
    public Response createCategory(@Valid CategoryDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/categories - tenantId=%s, code=%s", tenantId, dto.code);

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
    public Response updateCategory(@PathParam("categoryId") UUID categoryId, @Valid CategoryDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/catalog/categories/%s - tenantId=%s", categoryId, tenantId);

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
    public Response deleteCategory(@PathParam("categoryId") UUID categoryId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/catalog/categories/%s - tenantId=%s", categoryId, tenantId);

        Optional<Category> categoryOpt = categoryRepository.findByIdOptional(categoryId);

        if (categoryOpt.isEmpty() || !categoryOpt.get().tenant.id.equals(tenantId)) {
            return Response.status(Status.NOT_FOUND).entity(
                    createProblemDetails("Category Not Found", "Category not found: " + categoryId, Status.NOT_FOUND))
                    .build();
        }

        Category category = categoryOpt.get();
        category.status = "deleted";
        categoryRepository.persist(category);

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
    public Response listCollections(@QueryParam("status") String status, @QueryParam("page") Integer page,
            @QueryParam("pageSize") Integer pageSize) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/collections - tenantId=%s, status=%s, page=%d, pageSize=%d", tenantId, status,
                page, pageSize);

        // Validate pagination parameters
        int pageNumber = page != null && page > 0 ? page - 1 : 0; // Convert to 0-indexed
        int size = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;

        List<Collection> collections;
        long totalItems;

        if (status != null && !status.isBlank()) {
            collections = collectionRepository.findByStatus(status, pageNumber, size);
            totalItems = collectionRepository.countByStatus(status);
        } else {
            collections = collectionRepository.findByCurrentTenant();
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
    public Response createCollection(@Valid CollectionDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/catalog/collections - tenantId=%s, code=%s", tenantId, dto.code);

        // Check for duplicate code
        if (collectionRepository.findByCode(dto.code).isPresent()) {
            return Response.status(Status.CONFLICT).entity(createProblemDetails("Duplicate Collection Code",
                    "Collection with code '" + dto.code + "' already exists", Status.CONFLICT)).build();
        }

        Collection collection = collectionMapper.toEntity(dto);
        collectionRepository.persist(collection);

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
    public Response getCollection(@PathParam("collectionId") UUID collectionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/catalog/collections/%s - tenantId=%s", collectionId, tenantId);

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
    public Response updateCollection(@PathParam("collectionId") UUID collectionId, @Valid CollectionDto dto) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/catalog/collections/%s - tenantId=%s", collectionId, tenantId);

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
    public Response deleteCollection(@PathParam("collectionId") UUID collectionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/catalog/collections/%s - tenantId=%s", collectionId, tenantId);

        Optional<Collection> collectionOpt = collectionRepository.findByIdAndTenant(collectionId);

        if (collectionOpt.isEmpty()) {
            return Response.status(Status.NOT_FOUND).entity(createProblemDetails("Collection Not Found",
                    "Collection not found: " + collectionId, Status.NOT_FOUND)).build();
        }

        Collection collection = collectionOpt.get();
        collection.status = "deleted";
        collectionRepository.persist(collection);

        return Response.noContent().build();
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
}
