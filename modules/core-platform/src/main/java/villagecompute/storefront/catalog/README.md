# Catalog Module

## Overview

The catalog module provides comprehensive product catalog management for the Village Storefront platform. It includes:

- **Product Management**: CRUD operations for products with variants, SKUs, pricing, and inventory policies
- **Category Management**: Hierarchical category organization with parent-child relationships
- **Collection Management**: Flexible product grouping for merchandising (manual and automatic)
- **Tenant Isolation**: All catalog data is automatically scoped to the current tenant with RLS enforcement
- **Validation**: Business rule enforcement for status transitions, slug uniqueness, and data integrity
- **Search & Pagination**: Efficient query builders with filtering, sorting, and pagination support

**References:**
- Task: I2.T1 - Catalog domain implementation
- ERD: `docs/diagrams/erd.mmd`
- Architecture: `docs/architecture/01_Blueprint_Foundation.md` (Section 4.0 - Core Components)

---

## Package Structure

```
villagecompute/storefront/
├── data/
│   ├── models/
│   │   ├── Product.java                    # Product entity with SKU, slug, status, metadata
│   │   ├── ProductVariant.java             # Variant entity with pricing, attributes, inventory policy
│   │   ├── Category.java                   # Category entity with hierarchical relationships
│   │   └── Collection.java                 # Collection entity (manual/automatic groupings)
│   └── repositories/
│       ├── ProductRepository.java          # Tenant-aware product queries with search
│       ├── ProductVariantRepository.java   # Variant queries by product/SKU
│       ├── CategoryRepository.java         # Category queries with hierarchy traversal
│       └── CollectionRepository.java       # Collection queries with published filter
├── services/
│   ├── CatalogService.java                 # Main orchestration service for catalog operations
│   ├── CatalogCacheService.java            # Caffeine-backed caching for catalog queries
│   ├── ValidationException.java            # Business rule validation exception
│   ├── validation/
│   │   └── CatalogValidator.java           # Status transitions, slug uniqueness, format validation
│   └── mappers/
│       ├── ProductMapper.java              # MapStruct mapper: Product → ProductSummary/Detail
│       ├── ProductVariantMapper.java       # MapStruct mapper: ProductVariant → DTO
│       ├── CategoryMapper.java             # MapStruct mapper: Category ↔ CategoryDto
│       └── CollectionMapper.java           # MapStruct mapper: Collection ↔ CollectionDto
└── api/
    └── types/
        ├── ProductSummary.java             # Summary DTO for product listings
        ├── ProductDetail.java              # Detailed DTO with variants/categories
        ├── ProductVariantDto.java          # Variant DTO with pricing/attributes
        ├── CategoryDto.java                # Category DTO with parent reference
        └── CollectionDto.java              # Collection DTO with publishing state
```

---

## Key Entities

### Product
Represents a sellable item in the catalog. A product must have at least one variant to be purchasable.

**Fields:**
- `id` (UUID): Primary key
- `tenant` (FK): Multi-tenant isolation
- `sku` (String): Unique SKU within tenant
- `name` (String): Product display name
- `slug` (String): URL-safe identifier (unique per tenant)
- `description` (Text): Product description
- `type` (String): physical | digital | service
- `status` (String): draft | active | archived | deleted
- `metadata` (JSONB): Custom attributes
- `seoTitle`, `seoDescription`: SEO optimization fields
- `version` (Long): Optimistic locking
- `createdAt`, `updatedAt`: Automatic timestamps

**Unique Constraints:**
- `uk_products_tenant_sku`: (tenant_id, sku)
- `uk_products_tenant_slug`: (tenant_id, slug)

### ProductVariant
Represents a specific variation of a product (e.g., size, color).

**Fields:**
- `id` (UUID): Primary key
- `tenant` (FK): Multi-tenant isolation
- `product` (FK): Parent product reference
- `sku` (String): Unique SKU within tenant
- `name` (String): Variant name
- `attributes` (JSONB): Variant options (e.g., {"color": "Red", "size": "Large"})
- `price`, `compareAtPrice`, `cost` (Decimal): Pricing fields
- `weight`, `weightUnit`: Shipping calculations
- `barcode`: Product identification
- `requiresShipping`, `taxable`: Flags for checkout logic
- `position`: Display ordering
- `status` (String): active | archived | deleted

**Unique Constraints:**
- `uk_product_variants_tenant_sku`: (tenant_id, sku)

### Category
Hierarchical product categorization for navigation.

**Fields:**
- `id` (UUID): Primary key
- `tenant` (FK): Multi-tenant isolation
- `parent` (FK): Self-referencing for hierarchy
- `code` (String): Unique code within tenant
- `name` (String): Display name
- `slug` (String): URL-safe identifier
- `description` (Text): Category description
- `displayOrder` (Integer): Sorting within parent
- `status` (String): draft | active | archived | deleted

**Unique Constraints:**
- `uk_categories_tenant_code`: (tenant_id, code)
- `uk_categories_tenant_slug`: (tenant_id, slug)

### Collection
Product groupings for merchandising and promotions.

**Fields:**
- `id` (UUID): Primary key
- `tenant` (FK): Multi-tenant isolation
- `code` (String): Unique code within tenant
- `name` (String): Collection display name
- `slug` (String): URL-safe identifier
- `description` (Text): Collection description
- `imageUrl` (String): Hero image
- `displayOrder` (Integer): Homepage ordering
- `collectionType` (String): manual | automatic
- `selectionRules` (JSONB): Auto-collection rules (future)
- `published` (Boolean): Visibility flag
- `publishedAt` (Timestamp): Publication date
- `status` (String): draft | active | archived | deleted
- `seoTitle`, `seoDescription`: SEO fields

**Unique Constraints:**
- `uk_collections_tenant_code`: (tenant_id, code)
- `uk_collections_tenant_slug`: (tenant_id, slug)

---

## Core Services

### CatalogService
Main business logic orchestrator for catalog operations.

**Product Operations:**
- `createProduct(Product)` - Validates + persists new product
- `updateProduct(UUID, Product)` - Updates with status transition validation
- `getProduct(UUID)` - Fetch by ID with tenant ownership check
- `getProductBySku(String)` - Fetch by SKU (tenant-scoped)
- `listActiveProducts(page, size)` - Paginated active products
- `searchProducts(term, page, size)` - Full-text search on name/SKU
- `countActiveProducts()` - Total count for pagination
- `deleteProduct(UUID)` - Soft delete (status = 'deleted')

**Category Operations:**
- `createCategory(Category)` - Create new category
- `updateCategory(UUID, Category)` - Update with validation
- `getRootCategories()` - Top-level categories
- `getChildCategories(UUID)` - Children of parent category

**Collection Operations:**
- `createCollection(Collection)` - Validates + persists new collection
- `updateCollection(UUID, Collection)` - Updates with validation
- `getCollection(UUID)` - Fetch by ID
- `getCollectionBySlug(String)` - Fetch by slug
- `listPublishedCollections()` - Storefront display collections
- `listCollectionsByStatus(status, page, size)` - Paginated by status
- `deleteCollection(UUID)` - Soft delete

**Features:**
- Cache invalidation via `CatalogCacheService` after mutations
- Metric emission via Micrometer
- Structured logging for observability
- All operations are `@Transactional`

### CatalogValidator
Business rule enforcement service.

**Status Transition Rules:**
- `draft` → `active`: Allowed
- `active` → `archived`: Allowed
- `archived` → `deleted`: Allowed
- `active` → `draft`: **Forbidden** (would break live storefronts)
- `active` → `deleted`: **Forbidden** (must archive first)
- `deleted` → *: **Forbidden** (deleted entities are immutable)

**Validation Methods:**
- `validateStatusTransition(current, new)` - Enforces state machine
- `validateProductSlugUniqueness(slug, excludeId)` - Tenant-scoped uniqueness
- `validateCategorySlugUniqueness(slug, excludeId)` - Tenant-scoped uniqueness
- `validateCollectionSlugUniqueness(slug, excludeId)` - Tenant-scoped uniqueness
- `validateSlugFormat(slug)` - Enforces `^[a-z0-9-]+$` pattern
- `validateProductType(type)` - Validates against physical|digital|service
- `validateCollectionType(type)` - Validates against manual|automatic

**Slug Format Rules:**
- Lowercase letters, numbers, and hyphens only
- Cannot start or end with hyphen
- No consecutive hyphens
- Null/empty slugs are allowed (slugs are optional)

### CatalogCacheService
Caffeine-backed caching layer for catalog queries.

**Features:**
- Tenant-scoped cache keys
- Automatic invalidation on mutations
- TTL and eviction policies
- Cache hit/miss metrics

---

## Repositories

All repositories extend `PanacheRepositoryBase` and enforce tenant isolation via `TenantContext`. Query methods use `Parameters.with()` for type-safe binding.

### ProductRepository
**Query Methods:**
- `findByCurrentTenant()` - All products for tenant
- `findActiveByCurrentTenant(page, size)` - Active products paginated
- `findBySku(sku)` - By SKU (tenant-scoped)
- `findBySlug(slug)` - By slug (tenant-scoped)
- `searchProducts(term, page, size)` - Search name/SKU (case-insensitive)
- `countSearchResults(term)` - Total search results
- `countByCurrentTenant()` - Total product count
- `countActiveByCurrentTenant()` - Active product count
- `findByIdAndTenant(id)` - Explicit tenant ownership check

### CategoryRepository
**Query Methods:**
- `findByCurrentTenant()` - All active categories
- `findByCode(code)` - By code (tenant-scoped)
- `findBySlug(slug)` - By slug (tenant-scoped)
- `findRootCategories()` - Top-level categories ordered by displayOrder
- `findByParent(parentId)` - Children of parent category

### CollectionRepository
**Query Methods:**
- `findByCurrentTenant()` - All collections for tenant
- `findByStatus(status, page, size)` - Filter + paginate by status
- `findByCode(code)` - By code (tenant-scoped)
- `findBySlug(slug)` - By slug (tenant-scoped)
- `findPublished()` - Published + active collections ordered by displayOrder
- `searchCollections(term, status, page, size)` - Search by name
- `countByStatus(status)` - Count by status
- `countByCurrentTenant()` - Total collection count
- `findByIdAndTenant(id)` - Explicit tenant ownership check

---

## DTO Mappers (MapStruct)

All mappers use `componentModel = "cdi"` for CDI injection and compile to native-image compatible code.

### ProductMapper
- `toSummary(Product)` → `ProductSummary`: Minimal fields for listings
- `toDetail(Product)` → `ProductDetail`: Full data with variants/categories

### CategoryMapper
- `toDto(Category)` → `CategoryDto`: Entity → DTO
- `toEntity(CategoryDto)` → `Category`: DTO → Entity (ignores tenant, timestamps)
- `updateEntityFromDto(dto, entity)`: Partial update for PATCH

### CollectionMapper
- `toDto(Collection)` → `CollectionDto`: Entity → DTO
- `toEntity(CollectionDto)` → `Collection`: DTO → Entity (ignores tenant, timestamps)
- `updateEntityFromDto(dto, entity)`: Partial update for PATCH

---

## Testing

### CatalogValidatorTest
Unit tests for validation logic:
- Status transition rules (21 tests)
- Slug uniqueness (product, category, collection)
- Slug format validation
- Product/collection type validation

### CollectionRepositoryTest
Integration tests for repository queries:
- Tenant isolation (12 tests)
- Query filtering (status, published, search)
- Pagination support
- Slug/code lookups

### CatalogServiceTest (existing)
Integration tests for service layer:
- CRUD operations with tenant checks
- Search functionality
- Pagination
- Soft delete behavior

**Coverage Target:** 80% line and branch coverage (enforced by SonarCloud)

---

## Usage Examples

### Creating a Product

```java
@Inject
CatalogService catalogService;

Product product = new Product();
product.sku = "WIDGET-001";
product.name = "Red Widget";
product.slug = "red-widget";
product.type = "physical";
product.status = "draft";
product.description = "A premium red widget";

Product created = catalogService.createProduct(product);
// Validation automatically enforced (slug format, uniqueness, type)
// Tenant assignment via @PrePersist hook
// Cache invalidation triggered
```

### Searching Products

```java
CatalogService.CatalogSearchResult results =
    catalogService.searchProducts("widget", 0, 20);

List<Product> products = results.products();
long totalCount = results.totalItems();
```

### Creating a Collection

```java
Collection collection = new Collection();
collection.code = "SUMMER-2026";
collection.name = "Summer Collection 2026";
collection.slug = "summer-2026";
collection.collectionType = "manual";
collection.published = true;
collection.status = "active";

Collection created = catalogService.createCollection(collection);
```

### Updating Product Status

```java
Product product = catalogService.getProduct(productId).orElseThrow();
product.status = "active";  // draft → active (allowed)

catalogService.updateProduct(productId, product);
// Validator checks status transition rules
// Throws ValidationException if transition is forbidden
```

---

## Architecture Notes

### Tenant Isolation
- **Entity Level:** All entities have `@ManyToOne Tenant tenant` FK
- **@PrePersist Hook:** Auto-populates tenant from `TenantContext` if null
- **Repository Level:** All queries filter by `TenantContext.getCurrentTenantId()`
- **Service Level:** Explicit tenant ownership checks in update/delete operations
- **Database Level:** PostgreSQL RLS policies enforce row-level security (future)

### Validation Strategy
- **Create:** Validate type, slug format, slug uniqueness
- **Update:** Validate status transitions, slug changes (format + uniqueness if changed)
- **Delete:** Soft delete by setting `status = 'deleted'`

### Status State Machine
```
draft ──> active ──> archived ──> deleted
          │
          └──X──> draft (forbidden - would break live storefronts)
          │
          └──X──> deleted (forbidden - must archive first)
```

### Caching Strategy
- **Cache Keys:** `tenant:{tenantId}:catalog:{entity}:{operation}`
- **Invalidation:** After all mutations (create, update, delete)
- **Provider:** Caffeine in-memory cache (no Redis dependency)
- **TTL:** Configurable per environment

### Slug Design
- **Purpose:** URL-friendly identifiers for SEO
- **Format:** Lowercase alphanumeric + hyphens (`^[a-z0-9-]+$`)
- **Uniqueness:** Per-tenant scope (different tenants can reuse slugs)
- **Optional:** Entities can exist without slugs (use ID-based URLs)

---

## Future Enhancements

- **Automatic Collections:** Evaluate `selectionRules` JSONB for dynamic product membership
- **Product-Category Many-to-Many:** Currently implied, needs junction table
- **Product-Collection Many-to-Many:** Needs junction table for product assignments
- **Inventory Integration:** Connect `ProductVariant` to `InventoryLevel` (Task I3)
- **Media Integration:** Link products/variants to `MediaAsset` (Task I4)
- **Consignment Integration:** Filter products by consignor ownership (Task I4)
- **Full-Text Search:** PostgreSQL `tsvector` for advanced search
- **Elasticsearch Integration:** For faceted search and filters

---

## Related Modules

- **Inventory Module (I3):** Multi-location stock tracking for variants
- **Consignment Module (I4):** Vendor-owned product tracking
- **Media Pipeline (I4):** Product/variant image management
- **Checkout Orchestrator (I5):** Cart + order processing with catalog lookups
- **Feature Flags:** Gates experimental catalog features

---

## References

- **Task:** I2.T1 - Catalog Domain Implementation
- **Architecture:** `docs/architecture/01_Blueprint_Foundation.md` (Section 4.0)
- **ERD:** `docs/diagrams/erd.mmd`
- **ADR-001:** Multi-tenant data isolation
- **Project Standards:** `docs/java-project-standards.adoc`
