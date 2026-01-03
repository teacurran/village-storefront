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
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.CategoryRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.data.repositories.ProductVariantRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

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
    CatalogCacheService catalogCacheService;

    @Inject
    MeterRegistry meterRegistry;

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

        productRepository.persist(product);
        catalogCacheService.invalidateTenantCache(tenantId, "product-created");

        LOG.infof("Product created successfully - tenantId=%s, productId=%s, sku=%s", tenantId, product.id,
                product.sku);
        meterRegistry.counter("catalog.product.created", "tenant_id", tenantId.toString()).increment();

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

        List<Product> results = productRepository.searchProducts(searchTerm, page, size);
        long total = productRepository.countSearchResults(searchTerm);
        meterRegistry.counter("catalog.product.search", "tenant_id", tenantId.toString()).increment();

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
