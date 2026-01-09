package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * End-to-end integration test for complete checkout flow.
 *
 * <p>
 * Tests the complete customer journey from cart creation through order completion:
 * <ol>
 * <li>Create cart and add items</li>
 * <li>Update cart quantities</li>
 * <li>Complete checkout with payment</li>
 * <li>Verify order details</li>
 * <li>Retrieve order from order list</li>
 * </ol>
 *
 * <p>
 * Validates session continuity, idempotency, state transitions, and data consistency across endpoints.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: E2E checkout flow test with RestAssured</li>
 * <li>OpenAPI: Cart + Checkout + Order endpoint specifications</li>
 * <li>ADR-003: Checkout saga pattern</li>
 * </ul>
 */
@QuarkusTest
class CheckoutE2ETest {

    private static final String SESSION_ID = UUID.randomUUID().toString();

    private static final String TEST_TENANT_SUBDOMAIN = "e2etest";

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;
    private String variantId1;
    private String variantId2;

    @BeforeEach
    @Transactional
    void setUp() {
        purgeTestData();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "e2etest";
        tenant.name = "E2E Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;

        // Set tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        enableCheckoutFlag(tenant);

        // Create test products and variants
        Product product1 = new Product();
        product1.tenant = tenant;
        product1.sku = "E2E-PRODUCT-1";
        product1.name = "E2E Test Product 1";
        product1.slug = "e2e-product-1";
        product1.type = "physical";
        product1.status = "active";
        product1.createdAt = OffsetDateTime.now();
        product1.updatedAt = OffsetDateTime.now();
        entityManager.persist(product1);

        ProductVariant variant1 = new ProductVariant();
        variant1.tenant = tenant;
        variant1.product = product1;
        variant1.sku = "E2E-VARIANT-1";
        variant1.name = "E2E Variant 1";
        variant1.price = new BigDecimal("29.99");
        variant1.status = "active";
        variant1.createdAt = OffsetDateTime.now();
        variant1.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant1);

        Product product2 = new Product();
        product2.tenant = tenant;
        product2.sku = "E2E-PRODUCT-2";
        product2.name = "E2E Test Product 2";
        product2.slug = "e2e-product-2";
        product2.type = "physical";
        product2.status = "active";
        product2.createdAt = OffsetDateTime.now();
        product2.updatedAt = OffsetDateTime.now();
        entityManager.persist(product2);

        ProductVariant variant2 = new ProductVariant();
        variant2.tenant = tenant;
        variant2.product = product2;
        variant2.sku = "E2E-VARIANT-2";
        variant2.name = "E2E Variant 2";
        variant2.price = new BigDecimal("49.99");
        variant2.status = "active";
        variant2.createdAt = OffsetDateTime.now();
        variant2.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant2);

        entityManager.flush();
        variantId1 = variant1.id.toString();
        variantId2 = variant2.id.toString();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        TenantContext.clear();
        purgeTestData();
    }

    private void purgeTestData() {
        entityManager.createQuery("DELETE FROM OrderLineItem oli WHERE oli.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM Order o WHERE o.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM PaymentIntent pi WHERE pi.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM IdempotencyKey ik WHERE ik.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM DomainEvent de WHERE de.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag ff WHERE ff.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem ci WHERE ci.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM Cart c WHERE c.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem pli WHERE pli.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch pb WHERE pb.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem csi WHERE csi.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor cg WHERE cg.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant pv WHERE pv.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM Product p WHERE p.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM User u WHERE u.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant t WHERE t.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
    }

    private void enableCheckoutFlag(Tenant tenant) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = tenant;
        flag.flagKey = "checkout.order-creation.enabled";
        flag.enabled = true;
        flag.owner = "tests";
        flag.description = "Enable checkout for integration tests";
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = flag.createdAt;
        entityManager.persist(flag);
    }

    private RequestSpecification request() {
        return given().header("Host", tenantSubdomain + ".villagecompute.com").header("X-Session-Id", SESSION_ID)
                .contentType(ContentType.JSON);
    }

    /**
     * Complete end-to-end checkout flow test.
     *
     * <p>
     * Validates the entire customer journey from empty cart to completed order.
     */
    @Test
    void completeCheckoutFlow_shouldCreateOrderSuccessfully() {
        // Step 1: Verify cart is empty initially
        request().when().get("/api/v1/cart").then().statusCode(404);

        // Step 2: Add first item to cart
        String addRequest1 = String.format("""
                {
                  "variantId": "%s",
                  "quantity": 2
                }
                """, variantId1);

        String itemId1 = request().body(addRequest1).when().post("/api/v1/cart/items").then().statusCode(201)
                .body("id", notNullValue()).body("quantity", equalTo(2)).body("unitPrice.amount", equalTo("29.99"))
                .extract().path("id");

        // Step 3: Add second item to cart
        String addRequest2 = String.format("""
                {
                  "variantId": "%s",
                  "quantity": 1
                }
                """, variantId2);

        request().body(addRequest2).when().post("/api/v1/cart/items").then().statusCode(201)
                .body("quantity", equalTo(1)).body("unitPrice.amount", equalTo("49.99"));

        // Step 4: Verify cart totals
        Response cartResponse = request().when().get("/api/v1/cart").then().statusCode(200)
                .body("itemCount", equalTo(2)).body("subtotal.amount", equalTo("109.97")) // (29.99 * 2) + (49.99 * 1)
                .body("subtotal.currency", equalTo("USD")).extract().response();

        String cartId = cartResponse.path("id");
        assertNotNull(cartId, "Cart ID should not be null");

        // Step 5: Update first item quantity
        String updateRequest = """
                {
                  "quantity": 3
                }
                """;

        request().body(updateRequest).when().patch("/api/v1/cart/items/" + itemId1).then().statusCode(200)
                .body("quantity", equalTo(3)).body("lineTotal.amount", equalTo("89.97")); // 29.99 * 3

        // Step 6: Verify updated cart totals
        request().when().get("/api/v1/cart").then().statusCode(200).body("itemCount", equalTo(2))
                .body("subtotal.amount", equalTo("139.96")); // (29.99 * 3) + (49.99 * 1)

        // Step 7: Complete checkout
        String idempotencyKey = UUID.randomUUID().toString();
        String checkoutRequest = String.format(
                """
                        {
                          "cartId": "%s",
                          "customerEmail": "e2e-test@example.com",
                          "shippingAddress": "{\\"line1\\":\\"456 E2E Ave\\",\\"city\\":\\"Los Angeles\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"90001\\",\\"country\\":\\"US\\"}",
                          "billingAddress": "{\\"line1\\":\\"456 E2E Ave\\",\\"city\\":\\"Los Angeles\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"90001\\",\\"country\\":\\"US\\"}",
                          "shippingAmount": 15.00,
                          "taxAmount": 12.60,
                          "currency": "USD",
                          "paymentMethodId": "pm_e2e_test_success"
                        }
                        """,
                cartId);

        Response checkoutResponse = request().header("X-Idempotency-Key", idempotencyKey).body(checkoutRequest).when()
                .post("/api/v1/checkout/commit").then().statusCode(201).header("Location", notNullValue())
                .body("orderId", notNullValue()).body("orderNumber", notNullValue()).body("status", notNullValue())
                .body("total.amount", notNullValue()).body("total.currency", equalTo("USD")).extract().response();

        String orderId = checkoutResponse.path("orderId");
        String orderNumber = checkoutResponse.path("orderNumber");

        assertNotNull(orderId, "Order ID should not be null");
        assertNotNull(orderNumber, "Order number should not be null");

        // Step 8: Verify cart is cleared (or order created with cart snapshot)
        // NOTE: Depending on implementation, cart may be cleared or marked as checked out
        request().when().get("/api/v1/cart").then().statusCode(200).body("itemCount", equalTo(0));

        // Step 9: Retrieve order details
        request().when().get("/api/v1/orders/" + orderId).then().statusCode(200).body("orderId", equalTo(orderId))
                .body("orderNumber", equalTo(orderNumber)).body("customerEmail", equalTo("e2e-test@example.com"))
                .body("lineItems.size()", equalTo(2)) // 2 distinct products
                .body("total.amount", notNullValue()).body("total.currency", equalTo("USD"))
                .body("paymentIntent", notNullValue());

        // Step 10: Verify order appears in list
        request().when().get("/api/v1/orders").then().statusCode(200).body("items.size()", equalTo(1))
                .body("items[0].orderId", equalTo(orderId)).body("items[0].orderNumber", equalTo(orderNumber));

        // Step 11: Verify idempotency - retry checkout should return cached result
        Response retryResponse = request().header("X-Idempotency-Key", idempotencyKey).body(checkoutRequest).when()
                .post("/api/v1/checkout/commit").then().statusCode(201).extract().response();

        String retryOrderId = retryResponse.path("orderId");
        assertEquals(orderId, retryOrderId, "Idempotent retry should return same order ID");

        // Step 12: Admin operations - list orders
        request().when().get("/api/v1/admin/orders").then().statusCode(200).body("items.size()", equalTo(1))
                .body("pagination.totalItems", equalTo(1));

        // Step 13: Admin operations - update order status
        String updateOrderRequest = """
                {
                  "status": "shipped",
                  "notes": "Shipped via UPS Ground"
                }
                """;

        request().body(updateOrderRequest).when().patch("/api/v1/admin/orders/" + orderId).then().statusCode(200)
                .body("status", equalTo("shipped")).body("notes", equalTo("Shipped via UPS Ground"));

        // Step 14: Verify updated order status
        request().when().get("/api/v1/orders/" + orderId).then().statusCode(200).body("status", equalTo("shipped"))
                .body("notes", equalTo("Shipped via UPS Ground"));
    }
}
