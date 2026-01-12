package villagecompute.storefront.api.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;

import villagecompute.storefront.data.models.Category;
import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductCategory;
import villagecompute.storefront.data.models.ProductCategory.ProductCategoryId;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.testsupport.PostgresTenantTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * OpenAPI contract tests for catalog storefront endpoints.
 */
@QuarkusTest
@QuarkusTestResource(
        value = PostgresTenantTestResource.class,
        restrictToAnnotatedClass = true)
class CatalogContractIT {

    private static final String API_BASE_PATH = "/api/v1/catalog";
    private static final String TECHGADGETS_HOST = "techgadgets.villagecompute.com";
    private static final String ARTISAN_HOST = "artisancrafts.villagecompute.com";
    private static final OpenApiValidationFilter CATALOG_OPENAPI_VALIDATOR = buildOpenApiValidator();

    @Inject
    EntityManager entityManager;

    private Tenant techGadgetsTenant;
    private Tenant artisanTenant;
    private Category electronicsCategory;
    private Category audioCategory;
    private Product wirelessEarbuds;
    private Product phoneCase;
    private Product usbCable;

    @BeforeEach
    @Transactional
    void setUp() {
        cleanDatabase();

        techGadgetsTenant = createTenant(UUID.fromString("a0000000-0000-0000-0000-000000000001"), "techgadgets",
                "Tech Gadgets Online");
        artisanTenant = createTenant(UUID.fromString("a0000000-0000-0000-0000-000000000002"), "artisancrafts",
                "Artisan Crafts Collective");

        electronicsCategory = createCategory(techGadgetsTenant, "ELECTRONICS", "Electronics", "electronics", null, 1);
        audioCategory = createCategory(techGadgetsTenant, "AUDIO", "Audio & Headphones", "audio-headphones",
                electronicsCategory, 2);

        wirelessEarbuds = createProduct(techGadgetsTenant, "WE-PRO-001", "ProSound Wireless Earbuds",
                "prosound-wireless-earbuds", "Premium wireless earbuds with ANC and 24hr battery life.");
        phoneCase = createProduct(techGadgetsTenant, "CASE-ULT-001", "Ultra-Slim Phone Case", "ultra-slim-phone-case",
                "Sleek case with military-grade drop protection.");
        usbCable = createProduct(techGadgetsTenant, "CABLE-USBC-001", "Braided USB-C Cable 6ft",
                "braided-usbc-cable-6ft", "Durable braided USB-C cable supporting fast charge.");

        addProductToCategory(wirelessEarbuds, audioCategory, 1);
        addProductToCategory(phoneCase, electronicsCategory, 1);
        addProductToCategory(usbCable, electronicsCategory, 2);

        createVariant(techGadgetsTenant, wirelessEarbuds, "WE-PRO-001-BLK", "ProSound Wireless Earbuds - Black",
                new BigDecimal("149.99"), "{\"color\": \"Black\"}", 150);
        createVariant(techGadgetsTenant, wirelessEarbuds, "WE-PRO-001-WHT", "ProSound Wireless Earbuds - White",
                new BigDecimal("149.99"), "{\"color\": \"White\"}", 120);
        createVariant(techGadgetsTenant, wirelessEarbuds, "WE-PRO-001-ROSE", "ProSound Wireless Earbuds - Rose Gold",
                new BigDecimal("149.99"), "{\"color\": \"Rose Gold\"}", 80);

        createVariant(techGadgetsTenant, phoneCase, "CASE-ULT-001-IP14-BLK", "Ultra-Slim Case - iPhone 14 Black",
                new BigDecimal("29.99"), "{\"model\": \"iPhone 14\", \"color\": \"Black\"}", 200);

        createVariant(techGadgetsTenant, usbCable, "CABLE-USBC-001-STND", "Braided USB-C Cable - Standard",
                new BigDecimal("19.99"), "{\"length\": \"6ft\"}", 500);

        enableFeatureFlag(techGadgetsTenant, "catalog.search.enabled");
        enableFeatureFlag(techGadgetsTenant, "catalog.variant-matrix.enabled");

        entityManager.flush();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void listProductsReturnsPagedResults() {
        catalogRequest(TECHGADGETS_HOST).when().get(API_BASE_PATH + "/products").then().statusCode(200)
                .contentType(ContentType.JSON).body("data", hasSize(greaterThanOrEqualTo(3)))
                .body("data[0].id", notNullValue()).body("data.price.amount", hasItems("149.99", "29.99", "19.99"))
                .body("data.price.currency", hasItems("USD")).body("pagination.page", equalTo(1))
                .body("pagination.pageSize", greaterThan(0)).body("pagination.totalItems", greaterThanOrEqualTo(3));
    }

    @Test
    void listProductsSupportsPagination() {
        catalogRequest(TECHGADGETS_HOST).queryParam("page", 1).queryParam("pageSize", 2).when()
                .get(API_BASE_PATH + "/products").then().statusCode(200).body("data", hasSize(2))
                .body("pagination.page", equalTo(1)).body("pagination.pageSize", equalTo(2));
    }

    @Test
    void listProductsFiltersByCategorySlug() {
        catalogRequest(TECHGADGETS_HOST).queryParam("category", audioCategory.slug).when()
                .get(API_BASE_PATH + "/products").then().statusCode(200).body("data", hasSize(1))
                .body("data[0].name", equalTo("ProSound Wireless Earbuds"));
    }

    @Test
    void listProductsSupportsSearchWhenFlagEnabled() {
        catalogRequest(TECHGADGETS_HOST).queryParam("search", "earbuds").when().get(API_BASE_PATH + "/products").then()
                .statusCode(200).body("data", hasSize(1)).body("data[0].name", containsString("Earbuds"));
    }

    @Test
    void listProductsSearchEmptyResult() {
        catalogRequest(TECHGADGETS_HOST).queryParam("search", "nonexistent-product-xyz").when()
                .get(API_BASE_PATH + "/products").then().statusCode(200).body("data", hasSize(0))
                .body("pagination.totalItems", equalTo(0));
    }

    @Test
    void listProductsTenantIsolation() {
        catalogRequest(ARTISAN_HOST).when().get(API_BASE_PATH + "/products").then().statusCode(200)
                .body("data", hasSize(0)).body("pagination.totalItems", equalTo(0));
    }

    @Test
    void getProductReturnsDetail() {
        catalogRequest(TECHGADGETS_HOST).pathParam("productId", wirelessEarbuds.id.toString()).when()
                .get(API_BASE_PATH + "/products/{productId}").then().statusCode(200)
                .body("id", equalTo(wirelessEarbuds.id.toString())).body("sku", equalTo("WE-PRO-001"))
                .body("name", equalTo("ProSound Wireless Earbuds")).body("variants", hasSize(3))
                .body("variants.name", hasItems("ProSound Wireless Earbuds - Black",
                        "ProSound Wireless Earbuds - White", "ProSound Wireless Earbuds - Rose Gold"));
    }

    @Test
    void getProductNotFoundReturnsProblemDetails() {
        catalogRequest(TECHGADGETS_HOST).pathParam("productId", UUID.randomUUID()).when()
                .get(API_BASE_PATH + "/products/{productId}").then().statusCode(404)
                .body("title", containsString("Product Not Found")).body("status", equalTo(404));
    }

    @Test
    void getProductRespectsTenantIsolation() {
        catalogRequest(ARTISAN_HOST).pathParam("productId", wirelessEarbuds.id.toString()).when()
                .get(API_BASE_PATH + "/products/{productId}").then().statusCode(404);
    }

    @Test
    void variantMatrixIncludesOptionAxesAndVariants() {
        catalogRequest(TECHGADGETS_HOST).pathParam("productId", wirelessEarbuds.id.toString()).when()
                .get(API_BASE_PATH + "/products/{productId}/variant-matrix").then().statusCode(200)
                .body("productId", equalTo(wirelessEarbuds.id.toString())).body("optionAxes", hasSize(1))
                .body("optionAxes[0].name", equalTo("color"))
                .body("optionAxes[0].values", containsInAnyOrder("Black", "White", "Rose Gold"))
                .body("variants", hasSize(3)).body("variants[0].sku", notNullValue())
                .body("variants[0].options.color", notNullValue());
    }

    @Test
    void variantMatrixReturnsNotFoundForUnknownProduct() {
        catalogRequest(TECHGADGETS_HOST).pathParam("productId", UUID.randomUUID()).when()
                .get(API_BASE_PATH + "/products/{productId}/variant-matrix").then().statusCode(404)
                .body("status", equalTo(404));
    }

    private static OpenApiValidationFilter buildOpenApiValidator() {
        Path moduleRelative = Path.of("api", "v1", "openapi.yaml");
        if (Files.exists(moduleRelative)) {
            return new OpenApiValidationFilter(moduleRelative.toAbsolutePath().toString());
        }

        Path repoRelative = Path.of("modules", "core-platform", "api", "v1", "openapi.yaml");
        if (Files.exists(repoRelative)) {
            return new OpenApiValidationFilter(repoRelative.toAbsolutePath().toString());
        }

        throw new IllegalStateException("OpenAPI spec not found for catalog contract tests");
    }

    private RequestSpecification catalogRequest(String host) {
        return given().filter(CATALOG_OPENAPI_VALIDATOR).header("Host", host).accept(ContentType.JSON)
                .contentType(ContentType.JSON);
    }

    private void cleanDatabase() {
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductCategory").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }

    private Tenant createTenant(UUID id, String subdomain, String name) {
        Tenant tenant = new Tenant();
        tenant.id = id;
        tenant.subdomain = subdomain;
        tenant.name = name;
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        return tenant;
    }

    private Category createCategory(Tenant tenant, String code, String name, String slug, Category parent, int order) {
        Category category = new Category();
        category.id = UUID.randomUUID();
        category.tenant = tenant;
        category.parent = parent;
        category.code = code;
        category.name = name;
        category.slug = slug;
        category.status = "active";
        category.displayOrder = order;
        category.createdAt = OffsetDateTime.now();
        category.updatedAt = OffsetDateTime.now();
        entityManager.persist(category);
        return category;
    }

    private Product createProduct(Tenant tenant, String sku, String name, String slug, String description) {
        Product product = new Product();
        product.id = UUID.randomUUID();
        product.tenant = tenant;
        product.sku = sku;
        product.name = name;
        product.slug = slug;
        product.description = description;
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        return product;
    }

    private void addProductToCategory(Product product, Category category, int displayOrder) {
        ProductCategory mapping = new ProductCategory();
        mapping.id = new ProductCategoryId(product.tenant.id, product.id, category.id);
        mapping.product = product;
        mapping.category = category;
        mapping.displayOrder = displayOrder;
        entityManager.persist(mapping);
    }

    private ProductVariant createVariant(Tenant tenant, Product product, String sku, String name, BigDecimal price,
            String attributes, int inventoryQuantity) {
        ProductVariant variant = new ProductVariant();
        variant.id = UUID.randomUUID();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = sku;
        variant.name = name;
        variant.price = price;
        variant.compareAtPrice = price.add(new BigDecimal("20.00"));
        variant.attributes = attributes;
        variant.requiresShipping = true;
        variant.taxable = true;
        variant.weight = new BigDecimal("0.15");
        variant.weightUnit = "lb";
        variant.position = 1;
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);

        InventoryLevel inventory = new InventoryLevel();
        inventory.id = UUID.randomUUID();
        inventory.tenant = tenant;
        inventory.variant = variant;
        inventory.location = "warehouse-main";
        inventory.quantity = inventoryQuantity;
        inventory.reserved = 0;
        inventory.createdAt = OffsetDateTime.now();
        inventory.updatedAt = OffsetDateTime.now();
        entityManager.persist(inventory);

        return variant;
    }

    private void enableFeatureFlag(Tenant tenant, String flagKey) {
        FeatureFlag flag = new FeatureFlag();
        OffsetDateTime now = OffsetDateTime.now();
        flag.tenant = tenant;
        flag.flagKey = flagKey;
        flag.enabled = true;
        flag.config = "{}";
        flag.createdAt = now;
        flag.updatedAt = now;
        flag.owner = "qa@villagecompute.dev";
        flag.riskLevel = "LOW";
        flag.reviewCadenceDays = 90;
        flag.lastReviewedAt = now;
        flag.description = "Enabled for contract tests";
        flag.rollbackInstructions = "Disable flag";
        entityManager.persist(flag);
    }
}
