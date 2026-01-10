package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.CartItem;
import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link CheckoutResource}.
 *
 * <p>
 * Tests cover HTTP contract compliance, error handling, and checkout API functionality including idempotency, saga
 * execution, and ProblemDetails responses.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Checkout endpoint integration tests with contract verification</li>
 * <li>OpenAPI: /checkout endpoint specifications</li>
 * <li>ADR-003: Checkout saga pattern</li>
 * </ul>
 */
@QuarkusTest
class CheckoutResourceTest {

    private static final String TEST_TENANT_SUBDOMAIN = "checkouttest";

    @Inject
    EntityManager entityManager;

    private String tenantSubdomain;
    private UUID cartId;
    private String variantId;

    @BeforeEach
    @Transactional
    void setUp() {
        purgeTestData();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "checkouttest";
        tenant.name = "Checkout Test Tenant";
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

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "CHECKOUT-TEST-PRODUCT";
        product.name = "Checkout Test Product";
        product.slug = "checkout-test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "CHECKOUT-TEST-VARIANT";
        variant.name = "Checkout Test Variant";
        variant.price = new BigDecimal("99.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);
        entityManager.flush();
        variantId = variant.id.toString();

        // Create test cart with items
        Cart cart = new Cart();
        cart.tenant = tenant;
        cart.sessionId = UUID.randomUUID().toString();
        cart.createdAt = OffsetDateTime.now();
        cart.updatedAt = OffsetDateTime.now();
        entityManager.persist(cart);

        CartItem cartItem = new CartItem();
        cartItem.tenant = tenant;
        cartItem.cart = cart;
        cartItem.variant = variant;
        cartItem.quantity = 2;
        cartItem.unitPrice = variant.price;
        cartItem.createdAt = OffsetDateTime.now();
        cartItem.updatedAt = OffsetDateTime.now();
        entityManager.persist(cartItem);

        entityManager.flush();
        cartId = cart.id;
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
        entityManager.createQuery("DELETE FROM ConsignmentItem ci WHERE ci.tenant.subdomain = :subdomain")
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
        return given().header("Host", tenantSubdomain + ".villagecompute.com").contentType(ContentType.JSON);
    }

    // ========================================
    // POST /checkout/commit Tests
    // ========================================

    @Test
    void commitCheckout_shouldCreateOrder() {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = String.format(
                """
                        {
                          "cartId": "%s",
                          "customerEmail": "test@example.com",
                          "shippingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "billingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "shippingAmount": 10.00,
                          "taxAmount": 8.50,
                          "currency": "USD",
                          "paymentMethodId": "pm_test_123456"
                        }
                        """,
                cartId);

        request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when().post("/api/v1/checkout/commit")
                .then().statusCode(201).header("Location", notNullValue()).body("orderId", notNullValue())
                .body("orderNumber", notNullValue()).body("status", notNullValue()).body("total.amount", notNullValue())
                .body("total.currency", equalTo("USD"));
    }

    @Test
    void commitCheckout_shouldRequireIdempotencyKey() {
        String requestBody = String.format(
                """
                        {
                          "cartId": "%s",
                          "customerEmail": "test@example.com",
                          "shippingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "billingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "paymentMethodId": "pm_test_123456"
                        }
                        """,
                cartId);

        request().body(requestBody).when().post("/api/v1/checkout/commit").then().statusCode(400)
                .body("title", equalTo("Bad Request")).body("status", equalTo(400));
    }

    @Test
    void commitCheckout_shouldReturn400ForInvalidCart() {
        String idempotencyKey = UUID.randomUUID().toString();
        String invalidCartId = UUID.randomUUID().toString();
        String requestBody = String.format(
                """
                        {
                          "cartId": "%s",
                          "customerEmail": "test@example.com",
                          "shippingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "billingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "paymentMethodId": "pm_test_123456"
                        }
                        """,
                invalidCartId);

        request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when().post("/api/v1/checkout/commit")
                .then().statusCode(400).body("title", equalTo("Bad Request"));
    }

    @Test
    void commitCheckout_shouldReturn400ForMissingEmail() {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = String.format(
                """
                        {
                          "cartId": "%s",
                          "shippingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "billingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "paymentMethodId": "pm_test_123456"
                        }
                        """,
                cartId);

        request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when().post("/api/v1/checkout/commit")
                .then().statusCode(400);
    }

    @Test
    void commitCheckout_shouldBeIdempotent() {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = String.format(
                """
                        {
                          "cartId": "%s",
                          "customerEmail": "test@example.com",
                          "shippingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "billingAddress": "{\\"line1\\":\\"123 Main St\\",\\"city\\":\\"San Francisco\\",\\"state\\":\\"CA\\",\\"postalCode\\":\\"94102\\",\\"country\\":\\"US\\"}",
                          "paymentMethodId": "pm_test_123456"
                        }
                        """,
                cartId);

        // First request should succeed
        String orderId1 = request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/checkout/commit").then().statusCode(201).extract().path("orderId");

        // Second request with same idempotency key should return cached result
        String orderId2 = request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/checkout/commit").then().statusCode(201).extract().path("orderId");

        // Both should return the same order ID
        assert orderId1.equals(orderId2);
    }
}
