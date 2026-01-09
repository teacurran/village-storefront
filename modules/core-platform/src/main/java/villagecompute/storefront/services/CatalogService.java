package villagecompute.storefront.services;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.Collection;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.CategoryRepository;
import villagecompute.storefront.data.repositories.CollectionRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.services.metrics.CatalogMetrics;
import villagecompute.storefront.services.validation.CatalogValidator;
import villagecompute.storefront.tenant.TenantContext;

/**
 * Service layer for catalog operations (products, variants, categories).
 *
 * <p>
 * Provides business logic for managing the product catalog including CRUD operations, search, and variant management.
 * All operations are tenant-scoped and include structured logging for observability.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T1: Catalog domain implementation</li>
 * <li>ADR-001: Tenant-scoped services</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogService {

    private static final Logger LOG = Logger.getLogger(CatalogService.class);

    @Inject
    ProductRepository productRepository;

    @Inject
    ProductVariantRepository variantRepository;

    @Inject
    CategoryRepository categoryRepository;

    @Inject
    CollectionRepository collectionRepository;

    @Inject
    CatalogValidator catalogValidator;

    @Inject
    CatalogCacheService catalogCacheService;

    @Inject
    CatalogMetrics catalogMetrics;

    // ========================================
    // Product Operations
    // ========================================

    /**
     * Create a new product.
     *
     * @param product
     *            product to create
     * @return created product with generated ID
     */
    @Transactional
    public Product createProduct(Product product) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating product - tenantId=%s, sku=%s, name=%s", tenantId, product.sku, product.name);

        // Validate before persisting
        catalogValidator.validateProductType(product.type);
        catalogValidator.validateSlugFormat(product.slug);
        catalogValidator.validateProductSlugUniqueness(product.slug, null);

        productRepository.persist(product);
        catalogCacheService.invalidateTenantCache(tenantId, "product-created");

        LOG.infof("Product created successfully - tenantId=%s, productId=%s, sku=%s", tenantId, product.id,
                product.sku);
        catalogMetrics.recordProductCreated(tenantId);

        return product;
    }

    /**
     * Update an existing product.
     *
     * @param productId
     *            product UUID
     * @param updatedProduct
     *            updated product data
     * @return updated product
     * @throws IllegalArgumentException
     *             if product not found
     */
    @Transactional
    public Product updateProduct(UUID productId, Product updatedProduct) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating product - tenantId=%s, productId=%s", tenantId, productId);

        Product product = productRepository.findByIdOptional(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        // Verify tenant ownership
        if (!product.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Product does not belong to current tenant");
        }

        // Validate status transition
        if (!product.status.equals(updatedProduct.status)) {
            catalogValidator.validateStatusTransition(product.status, updatedProduct.status);
        }

        // Validate slug if changed
        if (updatedProduct.slug != null && !updatedProduct.slug.equals(product.slug)) {
            catalogValidator.validateSlugFormat(updatedProduct.slug);
            catalogValidator.validateProductSlugUniqueness(updatedProduct.slug, productId);
        }

        // Update fields (selective update pattern)
        product.name = updatedProduct.name;
        product.description = updatedProduct.description;
        product.slug = updatedProduct.slug;
        product.status = updatedProduct.status;
        product.metadata = updatedProduct.metadata;
        product.seoTitle = updatedProduct.seoTitle;
        product.seoDescription = updatedProduct.seoDescription;
        product.updatedAt = OffsetDateTime.now();

        productRepository.persist(product);
        catalogCacheService.invalidateTenantCache(tenantId, "product-updated");

        LOG.infof("Product updated successfully - tenantId=%s, productId=%s", tenantId, productId);
        catalogMetrics.recordProductUpdated(tenantId);
        return product;
    }

    /**
     * Get product by ID.
     *
     * @param productId
     *            product UUID
     * @return product if found
     */
    public Optional<Product> getProduct(UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching product - tenantId=%s, productId=%s", tenantId, productId);

        Optional<Product> product = productRepository.findByIdOptional(productId);

        // Verify tenant ownership
        if (product.isPresent() && !product.get().tenant.id.equals(tenantId)) {
            LOG.warnf("Tenant mismatch detected - tenantId=%s, productId=%s, productTenantId=%s", tenantId, productId,
                    product.get().tenant.id);
            return Optional.empty();
        }

        return product;
    }

    /**
     * Get product by SKU.
     *
     * @param sku
     *            product SKU
     * @return product if found
     */
    public Optional<Product> getProductBySku(String sku) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching product by SKU - tenantId=%s, sku=%s", tenantId, sku);

        return productRepository.findBySku(sku);
    }

    /**
     * Get product by slug.
     *
     * @param slug
     *            product slug
     * @return product if found
     */
    public Optional<Product> getProductBySlug(String slug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching product by slug - tenantId=%s, slug=%s", tenantId, slug);

        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }

        return productRepository.findBySlug(slug);
    }

    /**
     * List active products with pagination.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of active products
     */
    public List<Product> listActiveProducts(int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing active products - tenantId=%s, page=%d, size=%d", tenantId, page, size);

        return productRepository.findActiveByCurrentTenant(page, size);
    }

    /**
     * List active products with optional category and/or collection filters.
     *
     * @param categorySlug
     *            optional category slug filter
     * @param collectionSlug
     *            optional collection slug filter
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return catalog result including total items
     */
    public CatalogSearchResult listProducts(String categorySlug, String collectionSlug, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        boolean hasCategory = categorySlug != null && !categorySlug.isBlank();
        boolean hasCollection = collectionSlug != null && !collectionSlug.isBlank();

        LOG.debugf("Listing products with filters - tenantId=%s, category=%s, collection=%s, page=%d, size=%d",
                tenantId, categorySlug, collectionSlug, page, size);

        List<Product> products;
        long total;

        if (hasCategory && hasCollection) {
            products = productRepository.findActiveByCategoryAndCollection(categorySlug, collectionSlug, page, size);
            total = productRepository.countActiveByCategoryAndCollection(categorySlug, collectionSlug);
        } else if (hasCategory) {
            products = productRepository.findActiveByCategorySlug(categorySlug, page, size);
            total = productRepository.countActiveByCategorySlug(categorySlug);
        } else if (hasCollection) {
            products = productRepository.findActiveByCollectionSlug(collectionSlug, page, size);
            total = productRepository.countActiveByCollectionSlug(collectionSlug);
        } else {
            products = listActiveProducts(page, size);
            total = countActiveProducts();
        }

        return new CatalogSearchResult(products, total);
    }

    /**
     * Search products by keyword and return total count for pagination purposes.
     *
     * @param searchTerm
     *            search term
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return search result with products and total count
     */
    public CatalogSearchResult searchProducts(String searchTerm, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Searching products - tenantId=%s, term=%s, page=%d, size=%d", tenantId, searchTerm, page, size);

        long startTime = System.currentTimeMillis();
        List<Product> results = productRepository.searchProducts(searchTerm, page, size);
        long total = productRepository.countSearchResults(searchTerm);
        long duration = System.currentTimeMillis() - startTime;

        catalogMetrics.recordProductSearch(tenantId, duration);
        catalogMetrics.recordProductSearchResults(tenantId, results.size());

        return new CatalogSearchResult(results, total);
    }

    /**
     * Count active products for the current tenant.
     *
     * @return total number of active products
     */
    public long countActiveProducts() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return productRepository.countActiveByCurrentTenant();
    }

    /**
     * Delete a product (soft delete by setting status to 'deleted').
     *
     * @param productId
     *            product UUID
     */
    @Transactional
    public void deleteProduct(UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Deleting product - tenantId=%s, productId=%s", tenantId, productId);

        Product product = productRepository.findByIdOptional(productId)
                .orElseThrow(() -> new IllegalArgumentException("Product not found: " + productId));

        if (!product.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Product does not belong to current tenant");
        }

        product.status = "deleted";
        productRepository.persist(product);
        catalogCacheService.invalidateTenantCache(tenantId, "product-deleted");

        LOG.infof("Product deleted successfully - tenantId=%s, productId=%s", tenantId, productId);
        catalogMetrics.recordProductDeleted(tenantId);
    }

    // ========================================
    // Category Operations
    // ========================================

    /**
     * Create a new category.
     *
     * @param category
     *            category to create
     * @return created category
     */
    @Transactional
    public Category createCategory(Category category) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating category - tenantId=%s, code=%s, name=%s", tenantId, category.code, category.name);

        categoryRepository.persist(category);

        LOG.infof("Category created successfully - tenantId=%s, categoryId=%s", tenantId, category.id);
        return category;
    }

    /**
     * Get all root categories (no parent).
     *
     * @return list of root categories
     */
    public List<Category> getRootCategories() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching root categories - tenantId=%s", tenantId);

        return categoryRepository.findRootCategories();
    }

    /**
     * Get child categories of a parent.
     *
     * @param parentId
     *            parent category UUID
     * @return list of child categories
     */
    public List<Category> getChildCategories(UUID parentId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching child categories - tenantId=%s, parentId=%s", tenantId, parentId);

        return categoryRepository.findByParent(parentId);
    }

    /**
     * Update an existing category.
     *
     * @param categoryId
     *            category UUID
     * @param updatedCategory
     *            updated category data
     * @return updated category
     * @throws IllegalArgumentException
     *             if category not found
     */
    @Transactional
    public Category updateCategory(UUID categoryId, Category updatedCategory) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating category - tenantId=%s, categoryId=%s", tenantId, categoryId);

        Category category = categoryRepository.findByIdOptional(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + categoryId));

        if (!category.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Category does not belong to current tenant");
        }

        // Validate status transition
        if (!category.status.equals(updatedCategory.status)) {
            catalogValidator.validateStatusTransition(category.status, updatedCategory.status);
        }

        // Validate slug if changed
        if (updatedCategory.slug != null && !updatedCategory.slug.equals(category.slug)) {
            catalogValidator.validateSlugFormat(updatedCategory.slug);
            catalogValidator.validateCategorySlugUniqueness(updatedCategory.slug, categoryId);
        }

        category.name = updatedCategory.name;
        category.slug = updatedCategory.slug;
        category.description = updatedCategory.description;
        category.displayOrder = updatedCategory.displayOrder;
        category.status = updatedCategory.status;
        category.updatedAt = OffsetDateTime.now();

        categoryRepository.persist(category);
        catalogCacheService.invalidateTenantCache(tenantId, "category-updated");

        LOG.infof("Category updated successfully - tenantId=%s, categoryId=%s", tenantId, categoryId);
        return category;
    }

    // ========================================
    // Collection Operations
    // ========================================

    /**
     * Create a new collection.
     *
     * @param collection
     *            collection to create
     * @return created collection
     */
    @Transactional
    public Collection createCollection(Collection collection) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating collection - tenantId=%s, code=%s, name=%s", tenantId, collection.code, collection.name);

        // Validate before persisting
        catalogValidator.validateCollectionType(collection.collectionType);
        catalogValidator.validateSlugFormat(collection.slug);
        catalogValidator.validateCollectionSlugUniqueness(collection.slug, null);

        collectionRepository.persist(collection);
        catalogCacheService.invalidateTenantCache(tenantId, "collection-created");

        LOG.infof("Collection created successfully - tenantId=%s, collectionId=%s", tenantId, collection.id);
        catalogMetrics.recordCollectionCreated(tenantId);

        return collection;
    }

    /**
     * Update an existing collection.
     *
     * @param collectionId
     *            collection UUID
     * @param updatedCollection
     *            updated collection data
     * @return updated collection
     * @throws IllegalArgumentException
     *             if collection not found
     */
    @Transactional
    public Collection updateCollection(UUID collectionId, Collection updatedCollection) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating collection - tenantId=%s, collectionId=%s", tenantId, collectionId);

        Collection collection = collectionRepository.findByIdOptional(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found: " + collectionId));

        if (!collection.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Collection does not belong to current tenant");
        }

        // Validate status transition
        if (!collection.status.equals(updatedCollection.status)) {
            catalogValidator.validateStatusTransition(collection.status, updatedCollection.status);
        }

        // Validate slug if changed
        if (updatedCollection.slug != null && !updatedCollection.slug.equals(collection.slug)) {
            catalogValidator.validateSlugFormat(updatedCollection.slug);
            catalogValidator.validateCollectionSlugUniqueness(updatedCollection.slug, collectionId);
        }

        collection.name = updatedCollection.name;
        collection.slug = updatedCollection.slug;
        collection.description = updatedCollection.description;
        collection.imageUrl = updatedCollection.imageUrl;
        collection.displayOrder = updatedCollection.displayOrder;
        collection.collectionType = updatedCollection.collectionType;
        collection.selectionRules = updatedCollection.selectionRules;
        collection.published = updatedCollection.published;
        collection.publishedAt = updatedCollection.publishedAt;
        collection.status = updatedCollection.status;
        collection.seoTitle = updatedCollection.seoTitle;
        collection.seoDescription = updatedCollection.seoDescription;
        collection.updatedAt = OffsetDateTime.now();

        collectionRepository.persist(collection);
        catalogCacheService.invalidateTenantCache(tenantId, "collection-updated");

        LOG.infof("Collection updated successfully - tenantId=%s, collectionId=%s", tenantId, collectionId);
        catalogMetrics.recordCollectionUpdated(tenantId);
        return collection;
    }

    /**
     * Get collection by ID.
     *
     * @param collectionId
     *            collection UUID
     * @return collection if found
     */
    public Optional<Collection> getCollection(UUID collectionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching collection - tenantId=%s, collectionId=%s", tenantId, collectionId);

        Optional<Collection> collection = collectionRepository.findByIdOptional(collectionId);

        if (collection.isPresent() && !collection.get().tenant.id.equals(tenantId)) {
            LOG.warnf("Tenant mismatch detected - tenantId=%s, collectionId=%s", tenantId, collectionId);
            return Optional.empty();
        }

        return collection;
    }

    /**
     * Get collection by slug.
     *
     * @param slug
     *            collection slug
     * @return collection if found
     */
    public Optional<Collection> getCollectionBySlug(String slug) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching collection by slug - tenantId=%s, slug=%s", tenantId, slug);

        return collectionRepository.findBySlug(slug);
    }

    /**
     * List published collections for storefront display.
     *
     * @return list of published collections
     */
    public List<Collection> listPublishedCollections() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing published collections - tenantId=%s", tenantId);

        return collectionRepository.findPublished();
    }

    /**
     * List collections by status with pagination.
     *
     * @param status
     *            collection status
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of collections
     */
    public List<Collection> listCollectionsByStatus(String status, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing collections by status - tenantId=%s, status=%s, page=%d, size=%d", tenantId, status, page,
                size);

        return collectionRepository.findByStatus(status, page, size);
    }

    /**
     * Delete a collection (soft delete by setting status to 'deleted').
     *
     * @param collectionId
     *            collection UUID
     */
    @Transactional
    public void deleteCollection(UUID collectionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Deleting collection - tenantId=%s, collectionId=%s", tenantId, collectionId);

        Collection collection = collectionRepository.findByIdOptional(collectionId)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found: " + collectionId));

        if (!collection.tenant.id.equals(tenantId)) {
            throw new IllegalArgumentException("Collection does not belong to current tenant");
        }

        collection.status = "deleted";
        collectionRepository.persist(collection);
        catalogCacheService.invalidateTenantCache(tenantId, "collection-deleted");

        LOG.infof("Collection deleted successfully - tenantId=%s, collectionId=%s", tenantId, collectionId);
        catalogMetrics.recordCollectionDeleted(tenantId);
    }

    // ========================================
    // Variant Operations
    // ========================================

    /**
     * Create a new product variant.
     *
     * @param variant
     *            variant to create
     * @return created variant
     */
    @Transactional
    public ProductVariant createVariant(ProductVariant variant) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating variant - tenantId=%s, productId=%s, sku=%s", tenantId, variant.product.id, variant.sku);

        variantRepository.persist(variant);
        catalogCacheService.invalidateTenantCache(tenantId, "variant-created");

        LOG.infof("Variant created successfully - tenantId=%s, variantId=%s, sku=%s", tenantId, variant.id,
                variant.sku);
        catalogMetrics.recordVariantCreated(tenantId);
        return variant;
    }

    /**
     * Get all variants for a product.
     *
     * @param productId
     *            product UUID
     * @return list of variants
     */
    public List<ProductVariant> getProductVariants(UUID productId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching product variants - tenantId=%s, productId=%s", tenantId, productId);

        return variantRepository.findByProduct(productId);
    }

    /**
     * Get variant by SKU.
     *
     * @param sku
     *            variant SKU
     * @return variant if found
     */
    public Optional<ProductVariant> getVariantBySku(String sku) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Fetching variant by SKU - tenantId=%s, sku=%s", tenantId, sku);

        return variantRepository.findBySku(sku);
    }

    /**
     * Value object encapsulating catalog search results.
     */
    public record CatalogSearchResult(List<Product> products, long totalItems) {
    }
}
