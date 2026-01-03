package villagecompute.storefront;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.CartRepository;
import villagecompute.storefront.data.repositories.ProductRepository;
import villagecompute.storefront.services.CartService;
import villagecompute.storefront.services.CatalogService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;
import villagecompute.storefront.testsupport.PostgresTenantTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * End-to-end integration tests proving tenant isolation across repository, service, HTTP, and PostgreSQL RLS layers.
 */
@QuarkusTest
@QuarkusTestResource(
        value = PostgresTenantTestResource.class,
        restrictToAnnotatedClass = true)
class TenantIsolationIT {

    private static final String TENANT_A_SUBDOMAIN = "tenant-a-iso";
    private static final String TENANT_B_SUBDOMAIN = "tenant-b-iso";
    private static final String SESSION_A = "aaaaaaaa-5555-6666-7777-888888888888";
    private static final String SESSION_B = "bbbbbbbb-5555-6666-7777-888888888888";
    private static final String SESSION_HEADER = "X-Session-Id";
    private static final String POSTGRES_TENANT_SETTING = "app.current_tenant_id";
    private static final List<String> RLS_TABLES = List.of("products", "product_variants", "carts", "cart_items");

    @Inject
    EntityManager entityManager;

    @Inject
    CatalogService catalogService;

    @Inject
    CartService cartService;

    @Inject
    ProductRepository productRepository;

    @Inject
    CartRepository cartRepository;

    private Tenant tenantA;
    private Tenant tenantB;
    private Product productA;
    private Product productB;
    private ProductVariant variantA;
    private ProductVariant variantB;

    @BeforeEach
    @Transactional
    void setUp() {
        purgeData();
        tenantA = createTenant(TENANT_A_SUBDOMAIN, "Tenant A Isolation Test");
        tenantB = createTenant(TENANT_B_SUBDOMAIN, "Tenant B Isolation Test");

        useTenantContext(tenantA);
        productA = createAndPersistProduct(tenantA, "A-WIDGET-001", "Tenant A Widget");
        variantA = createAndPersistVariant(tenantA, productA, "A-VAR-001", "Tenant A Variant", new BigDecimal("19.99"));

        useTenantContext(tenantB);
        productB = createAndPersistProduct(tenantB, "B-WIDGET-001", "Tenant B Widget");
        variantB = createAndPersistVariant(tenantB, productB, "B-VAR-001", "Tenant B Variant", new BigDecimal("29.99"));

        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --------------------------------------------------
    // Catalog Service + Repository Isolation
    // --------------------------------------------------

    @Test
    @Transactional
    void catalogService_shouldNotFindProductFromDifferentTenant() {
        useTenantContext(tenantA);
        Optional<Product> result = catalogService.getProduct(productB.id);
        assertFalse(result.isPresent(), "Tenant A should not load Tenant B product by ID");
    }

    @Test
    @Transactional
    void catalogService_shouldOnlyReturnCurrentTenantProducts() {
        useTenantContext(tenantA);
        List<Product> productsA = catalogService.listActiveProducts(0, 100);
        assertEquals(1, productsA.size());
        assertEquals("A-WIDGET-001", productsA.get(0).sku);

        useTenantContext(tenantB);
        List<Product> productsB = catalogService.listActiveProducts(0, 100);
        assertEquals(1, productsB.size());
        assertEquals("B-WIDGET-001", productsB.get(0).sku);
    }

    @Test
    @Transactional
    void catalogService_shouldBlockUpdateForDifferentTenantProduct() {
        useTenantContext(tenantA);
        productB.name = "Malicious Update";
        assertThrows(IllegalArgumentException.class, () -> catalogService.updateProduct(productB.id, productB));
    }

    @Test
    @Transactional
    void catalogService_shouldBlockDeleteForDifferentTenantProduct() {
        useTenantContext(tenantA);
        assertThrows(IllegalArgumentException.class, () -> catalogService.deleteProduct(productB.id));
    }

    @Test
    @Transactional
    void catalogService_shouldNotFindProductBySkuFromDifferentTenant() {
        useTenantContext(tenantA);
        Optional<Product> result = catalogService.getProductBySku("B-WIDGET-001");
        assertFalse(result.isPresent(), "Tenant A should not resolve Tenant B SKU");
    }

    @Test
    @Transactional
    void productRepository_shouldFilterCustomQueriesByTenantContext() {
        useTenantContext(tenantA);
        List<Product> products = productRepository.findByCurrentTenant();
        assertEquals(1, products.size());
        assertEquals(tenantA.id, products.get(0).tenant.id);
    }

    @Test
    @Transactional
    void repositoryQueries_shouldThrowWhenNoTenantContext() {
        TenantContext.clear();
        assertThrows(IllegalStateException.class, () -> productRepository.findByCurrentTenant());
        assertThrows(IllegalStateException.class, () -> cartRepository.findByCurrentTenant());
    }

    // --------------------------------------------------
    // Cart Service + Repository Isolation
    // --------------------------------------------------

    @Test
    @Transactional
    void cartRepository_shouldFilterCustomQueriesByTenantContext() {
        useTenantContext(tenantA);
        cartService.getOrCreateCartForSession(SESSION_A);

        useTenantContext(tenantB);
        cartService.getOrCreateCartForSession(SESSION_B);

        List<Cart> carts = cartRepository.findByCurrentTenant();
        assertEquals(1, carts.size());
        assertEquals(tenantB.id, carts.get(0).tenant.id);
    }

    @Test
    @Transactional
    void cartService_shouldOnlyReturnCurrentTenantCarts() {
        useTenantContext(tenantA);
        cartService.getOrCreateCartForSession(SESSION_A);
        List<Cart> cartsA = cartRepository.findByCurrentTenant();
        assertEquals(1, cartsA.size());
        assertEquals(tenantA.id, cartsA.get(0).tenant.id);

        useTenantContext(tenantB);
        cartService.getOrCreateCartForSession(SESSION_B);
        List<Cart> cartsB = cartRepository.findByCurrentTenant();
        assertEquals(1, cartsB.size());
        assertEquals(tenantB.id, cartsB.get(0).tenant.id);
    }

    @Test
    @Transactional
    void cartService_shouldNotFindCartBySessionFromDifferentTenant() {
        useTenantContext(tenantA);
        cartService.getOrCreateCartForSession(SESSION_A);

        useTenantContext(tenantB);
        Optional<Cart> result = cartRepository.findBySession(SESSION_A);
        assertFalse(result.isPresent(), "Tenant B must not see Tenant A cart session");
    }

    // --------------------------------------------------
    // HTTP Cart API Isolation
    // --------------------------------------------------

    @Test
    void cartApi_shouldNotAccessCartFromDifferentTenant() {
        String addRequestA = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variantA.id);
        String cartItemIdA = requestForTenant(TENANT_A_SUBDOMAIN, SESSION_A).body(addRequestA)
                .post("/api/v1/cart/items").then().statusCode(201).extract().path("id");

        requestForTenant(TENANT_B_SUBDOMAIN, SESSION_A).when().get("/api/v1/cart").then().statusCode(404);
        requestForTenant(TENANT_B_SUBDOMAIN, SESSION_A).when().delete("/api/v1/cart/items/" + cartItemIdA).then()
                .statusCode(404);
    }

    @Test
    void cartApi_shouldIsolateCartItemsByTenant() {
        String addRequestA = String.format("{\"variantId\":\"%s\",\"quantity\":2}", variantA.id);
        requestForTenant(TENANT_A_SUBDOMAIN, SESSION_A).body(addRequestA).post("/api/v1/cart/items").then()
                .statusCode(201);

        String addRequestB = String.format("{\"variantId\":\"%s\",\"quantity\":3}", variantB.id);
        requestForTenant(TENANT_B_SUBDOMAIN, SESSION_B).body(addRequestB).post("/api/v1/cart/items").then()
                .statusCode(201);

        requestForTenant(TENANT_A_SUBDOMAIN, SESSION_A).when().get("/api/v1/cart").then().statusCode(200)
                .body("itemCount", equalTo(1)).body("items[0].variantId", equalTo(variantA.id.toString()))
                .body("subtotal.amount", equalTo("39.98"));

        requestForTenant(TENANT_B_SUBDOMAIN, SESSION_B).when().get("/api/v1/cart").then().statusCode(200)
                .body("itemCount", equalTo(1)).body("items[0].variantId", equalTo(variantB.id.toString()))
                .body("subtotal.amount", equalTo("89.97"));
    }

    @Test
    void cartApi_shouldBlockAddingVariantFromDifferentTenant() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variantB.id);
        requestForTenant(TENANT_A_SUBDOMAIN, SESSION_A).body(addRequest).post("/api/v1/cart/items").then()
                .statusCode(404);
    }

    @Test
    void cartApi_shouldBlockCrossSessionAccessWithinSameTenant() {
        String addRequest = String.format("{\"variantId\":\"%s\",\"quantity\":1}", variantA.id);
        String itemId = requestForTenant(TENANT_A_SUBDOMAIN, SESSION_A).body(addRequest).post("/api/v1/cart/items")
                .then().statusCode(201).extract().path("id");

        String differentSession = "cccccccc-5555-6666-7777-888888888888";
        requestForTenant(TENANT_A_SUBDOMAIN, differentSession).when().delete("/api/v1/cart/items/" + itemId).then()
                .statusCode(404);
    }

    // --------------------------------------------------
    // PostgreSQL RLS Verification
    // --------------------------------------------------

    @Test
    @Transactional
    void rlsPolicy_shouldBlockRawQueryAccessToDifferentTenantData() {
        enableRowLevelSecurityPolicies();

        useTenantContext(tenantA);
        setDatabaseTenant(tenantA.id);
        @SuppressWarnings("unchecked")
        List<Product> protectedResults = entityManager.createQuery("SELECT p FROM Product p WHERE p.id = :productId")
                .setParameter("productId", productB.id).getResultList();
        assertTrue(protectedResults.isEmpty(),
                "PostgreSQL RLS must block Tenant A from selecting Tenant B product via raw query");

        disableRowLevelSecurityPolicies();
        setDatabaseTenant(tenantA.id);
        @SuppressWarnings("unchecked")
        List<Product> leakedResults = entityManager.createQuery("SELECT p FROM Product p WHERE p.id = :productId")
                .setParameter("productId", productB.id).getResultList();
        assertFalse(leakedResults.isEmpty(), "Disabling RLS should expose cross-tenant leak for documentation");
    }

    // --------------------------------------------------
    // Helper methods
    // --------------------------------------------------

    private void useTenantContext(Tenant tenant) {
        TenantContext.setCurrentTenant(toTenantInfo(tenant));
    }

    private TenantInfo toTenantInfo(Tenant tenant) {
        return new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status);
    }

    private Tenant createTenant(String subdomain, String name) {
        Tenant tenant = new Tenant();
        tenant.subdomain = subdomain;
        tenant.name = name;
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        return tenant;
    }

    private void purgeData() {
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }

    private RequestSpecification requestForTenant(String subdomain, String sessionId) {
        return given().header("Host", subdomain + ".villagecompute.com").header(SESSION_HEADER, sessionId)
                .contentType(ContentType.JSON);
    }

    private Product createAndPersistProduct(Tenant tenant, String sku, String name) {
        Product product = new Product();
        product.tenant = tenant;
        product.sku = sku;
        product.name = name;
        product.slug = name.toLowerCase().replace(" ", "-");
        product.description = "Test description for " + name;
        product.type = "physical";
        product.status = "active";
        product.metadata = "{}";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);
        entityManager.flush();
        return product;
    }

    private ProductVariant createAndPersistVariant(Tenant tenant, Product product, String sku, String name,
            BigDecimal price) {
        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = sku;
        variant.name = name;
        variant.price = price;
        variant.requiresShipping = true;
        variant.taxable = true;
        variant.position = 0;
        variant.status = "active";
        variant.attributes = "{}";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        return variant;
    }

    private void enableRowLevelSecurityPolicies() {
        for (String table : RLS_TABLES) {
            entityManager.createNativeQuery("DROP POLICY IF EXISTS tenant_isolation_policy ON " + table)
                    .executeUpdate();
            entityManager.createNativeQuery("""
                    CREATE POLICY tenant_isolation_policy ON %s
                        USING (
                            current_setting('%s', true) IS NOT NULL
                            AND tenant_id = current_setting('%s', true)::uuid
                        )
                        WITH CHECK (
                            current_setting('%s', true) IS NOT NULL
                            AND tenant_id = current_setting('%s', true)::uuid
                        )
                    """.formatted(table, POSTGRES_TENANT_SETTING, POSTGRES_TENANT_SETTING, POSTGRES_TENANT_SETTING,
                    POSTGRES_TENANT_SETTING)).executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY").executeUpdate();
            entityManager.createNativeQuery("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY").executeUpdate();
        }
    }

    private void disableRowLevelSecurityPolicies() {
        for (String table : RLS_TABLES) {
            entityManager.createNativeQuery("ALTER TABLE " + table + " DISABLE ROW LEVEL SECURITY").executeUpdate();
        }
    }

    private void setDatabaseTenant(UUID tenantId) {
        entityManager
                .createNativeQuery(
                        "SELECT set_config('" + POSTGRES_TENANT_SETTING + "', CAST(:tenantId AS text), false)")
                .setParameter("tenantId", tenantId.toString()).getSingleResult();
    }

}
