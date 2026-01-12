package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.returns.ReturnInventoryTask;
import villagecompute.storefront.services.returns.ReturnInventoryTaskQueue;
import villagecompute.storefront.services.returns.ReturnNotificationTask;
import villagecompute.storefront.services.returns.ReturnNotificationTaskQueue;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Integration tests for {@link OrderResource}.
 *
 * <p>
 * Tests cover HTTP contract compliance, error handling, order retrieval, and admin operations including pagination,
 * filtering, status updates, and cancellation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Order endpoint integration tests with contract verification</li>
 * <li>OpenAPI: /orders and /admin/orders endpoint specifications</li>
 * </ul>
 */
@QuarkusTest
class OrderResourceTest {

    private static final String TEST_TENANT_SUBDOMAIN = "ordertest";

    @Inject
    EntityManager entityManager;

    @Inject
    ReturnInventoryTaskQueue returnInventoryTaskQueue;

    @Inject
    ReturnNotificationTaskQueue returnNotificationTaskQueue;

    private String tenantSubdomain;
    private UUID orderId;
    private String orderNumber;

    @BeforeEach
    @Transactional
    void setUp() {
        purgeTestData();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "ordertest";
        tenant.name = "Order Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantSubdomain = tenant.subdomain;

        // Set tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));
        returnInventoryTaskQueue.clear();
        returnNotificationTaskQueue.clear();

        Consignor consignor = new Consignor();
        consignor.tenant = tenant;
        consignor.name = "Order Test Consignor";
        consignor.contactInfo = "{\"email\":\"consignor@example.com\"}";
        consignor.status = "active";
        consignor.createdAt = OffsetDateTime.now();
        consignor.updatedAt = OffsetDateTime.now();
        entityManager.persist(consignor);

        // Create test product and variant
        Product product = new Product();
        product.tenant = tenant;
        product.sku = "ORDER-TEST-PRODUCT";
        product.name = "Order Test Product";
        product.slug = "order-test-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = OffsetDateTime.now();
        product.updatedAt = OffsetDateTime.now();
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.sku = "ORDER-TEST-VARIANT";
        variant.name = "Order Test Variant";
        variant.price = new BigDecimal("49.99");
        variant.status = "active";
        variant.createdAt = OffsetDateTime.now();
        variant.updatedAt = OffsetDateTime.now();
        entityManager.persist(variant);

        // Create test order
        Order order = new Order();
        order.tenant = tenant;
        order.orderNumber = "ORD-20260108-0001";
        order.status = Order.OrderStatus.PAID;
        order.customerEmail = "customer@example.com";
        order.shippingAddress = "{\"line1\":\"123 Main St\",\"city\":\"San Francisco\",\"state\":\"CA\",\"postalCode\":\"94102\",\"country\":\"US\"}";
        order.billingAddress = "{\"line1\":\"123 Main St\",\"city\":\"San Francisco\",\"state\":\"CA\",\"postalCode\":\"94102\",\"country\":\"US\"}";
        order.currency = "USD";
        order.subtotalAmount = new BigDecimal("49.99");
        order.taxAmount = new BigDecimal("4.25");
        order.shippingAmount = new BigDecimal("10.00");
        order.discountAmount = BigDecimal.ZERO;
        order.calculateTotal();
        order.paymentIntentId = "pi_test_123456";
        order.paidAt = OffsetDateTime.now();
        order.createdAt = OffsetDateTime.now();
        order.updatedAt = OffsetDateTime.now();
        entityManager.persist(order);

        // Create order line item
        OrderLineItem lineItem = new OrderLineItem();
        lineItem.tenant = tenant;
        lineItem.order = order;
        lineItem.productId = product.id;
        lineItem.variantId = variant.id;
        lineItem.productName = product.name;
        lineItem.variantName = variant.name;
        lineItem.sku = variant.sku;
        lineItem.quantity = 1;
        lineItem.vendorId = consignor.id;
        lineItem.unitPrice = variant.price;
        lineItem.calculateSubtotal();
        lineItem.createdAt = OffsetDateTime.now();
        lineItem.updatedAt = OffsetDateTime.now();
        entityManager.persist(lineItem);

        entityManager.flush();
        orderId = order.id;
        orderNumber = order.orderNumber;
    }

    @AfterEach
    @Transactional
    void tearDown() {
        TenantContext.clear();
        returnInventoryTaskQueue.clear();
        returnNotificationTaskQueue.clear();
        purgeTestData();
    }

    private void purgeTestData() {
        entityManager.createQuery("DELETE FROM ReturnAuthorization ra WHERE ra.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
        entityManager.createQuery("DELETE FROM Refund r WHERE r.tenant.subdomain = :subdomain")
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).executeUpdate();
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

    private RequestSpecification request() {
        return given().header("Host", tenantSubdomain + ".villagecompute.com").contentType(ContentType.JSON);
    }

    // ========================================
    // GET /orders/{orderId} Tests
    // ========================================

    @Test
    void getOrder_shouldReturnOrderDetails() {
        request().when().get("/api/v1/orders/" + orderId).then().statusCode(200).body("orderId", notNullValue())
                .body("orderNumber", equalTo(orderNumber)).body("status", notNullValue())
                .body("customerEmail", equalTo("customer@example.com")).body("lineItems", notNullValue())
                .body("total.amount", notNullValue()).body("total.currency", equalTo("USD"));
    }

    @Test
    void getOrder_shouldReturn404ForInvalidOrder() {
        UUID invalidId = UUID.randomUUID();
        request().when().get("/api/v1/orders/" + invalidId).then().statusCode(404).body("title", equalTo("Not Found"))
                .body("status", equalTo(404));
    }

    // ========================================
    // GET /orders Tests
    // ========================================

    @Test
    void listOrders_shouldReturnPaginatedOrders() {
        request().when().get("/api/v1/orders").then().statusCode(200).body("items", notNullValue())
                .body("items.size()", equalTo(1)).body("pagination", notNullValue()).body("pagination.page", equalTo(1))
                .body("pagination.totalItems", equalTo(1));
    }

    @Test
    void listOrders_shouldSupportPagination() {
        request().queryParam("page", 1).queryParam("pageSize", 10).when().get("/api/v1/orders").then().statusCode(200)
                .body("pagination.page", equalTo(1)).body("pagination.pageSize", equalTo(10));
    }

    @Test
    void listOrders_shouldSupportStatusFilter() {
        request().queryParam("status", "paid").when().get("/api/v1/orders").then().statusCode(200).body("items",
                notNullValue());
    }

    // ========================================
    // GET /admin/orders Tests
    // ========================================

    @Test
    void adminListOrders_shouldReturnAllTenantOrders() {
        request().when().get("/api/v1/admin/orders").then().statusCode(200).body("items", notNullValue())
                .body("items.size()", equalTo(1)).body("pagination", notNullValue());
    }

    @Test
    void adminListOrders_shouldSupportAdvancedFiltering() {
        request().queryParam("status", "paid").queryParam("sortBy", "created_at").queryParam("sortOrder", "desc").when()
                .get("/api/v1/admin/orders").then().statusCode(200).body("items", notNullValue());
    }

    @Test
    void adminListOrders_shouldSupportSearch() {
        request().queryParam("search", "customer@example.com").when().get("/api/v1/admin/orders").then().statusCode(200)
                .body("items", notNullValue());
    }

    // ========================================
    // GET /admin/orders/{orderId} Tests
    // ========================================

    @Test
    void adminGetOrder_shouldReturnOrderDetails() {
        request().when().get("/api/v1/admin/orders/" + orderId).then().statusCode(200).body("orderId", notNullValue())
                .body("orderNumber", equalTo(orderNumber));
    }

    // ========================================
    // PATCH /admin/orders/{orderId} Tests
    // ========================================

    @Test
    void adminUpdateOrder_shouldUpdateStatus() {
        String requestBody = """
                {
                  "status": "shipped"
                }
                """;

        request().body(requestBody).when().patch("/api/v1/admin/orders/" + orderId).then().statusCode(200)
                .body("orderId", equalTo(orderId.toString())).body("status", equalTo("shipped"));
    }

    @Test
    void adminUpdateOrder_shouldUpdateNotes() {
        String requestBody = """
                {
                  "notes": "Customer requested expedited shipping"
                }
                """;

        request().body(requestBody).when().patch("/api/v1/admin/orders/" + orderId).then().statusCode(200)
                .body("orderId", equalTo(orderId.toString()))
                .body("notes", equalTo("Customer requested expedited shipping"));
    }

    @Test
    void adminUpdateOrder_shouldReturn404ForInvalidOrder() {
        UUID invalidId = UUID.randomUUID();
        String requestBody = """
                {
                  "status": "shipped"
                }
                """;

        request().body(requestBody).when().patch("/api/v1/admin/orders/" + invalidId).then().statusCode(404);
    }

    // ========================================
    // POST /admin/orders/{orderId}/cancel Tests
    // ========================================

    @Test
    void adminCancelOrder_shouldCancelOrder() {
        String requestBody = """
                {
                  "reason": "Customer requested cancellation",
                  "refund": true
                }
                """;

        request().body(requestBody).when().post("/api/v1/admin/orders/" + orderId + "/cancel").then().statusCode(200)
                .body("orderId", equalTo(orderId.toString())).body("status", equalTo("cancelled"));
    }

    @Test
    void adminCancelOrder_shouldRequireReason() {
        String requestBody = """
                {
                  "refund": true
                }
                """;

        request().body(requestBody).when().post("/api/v1/admin/orders/" + orderId + "/cancel").then().statusCode(400)
                .body("title", equalTo("Bad Request"));
    }

    @Test
    void adminCancelOrder_shouldReturn404ForInvalidOrder() {
        UUID invalidId = UUID.randomUUID();
        String requestBody = """
                {
                  "reason": "Customer requested cancellation"
                }
                """;

        request().body(requestBody).when().post("/api/v1/admin/orders/" + invalidId + "/cancel").then().statusCode(404);
    }

    // ========================================
    // POST /admin/orders/{orderId}/refund Tests
    // ========================================

    @Test
    void adminRefundOrder_shouldCreateRefund() {
        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "amount": {
                    "amount": "64.24",
                    "currency": "USD"
                  },
                  "reason": "Defective item",
                  "restockItems": true
                }
                """;

        request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/admin/orders/" + orderId + "/refund").then().statusCode(201)
                .body("refundId", notNullValue()).body("status", notNullValue());
    }

    @Test
    void adminRefundOrder_shouldRequireIdempotencyKey() {
        String requestBody = """
                {
                  "amount": {
                    "amount": "64.24",
                    "currency": "USD"
                  },
                  "reason": "Defective item"
                }
                """;

        request().body(requestBody).when().post("/api/v1/admin/orders/" + orderId + "/refund").then().statusCode(400)
                .body("title", equalTo("Bad Request"));
    }

    @Test
    void adminRefundOrder_shouldHandleIdempotencyDuplicates() {
        // Setup payment intent for refund
        setupPaymentIntentTransactionally();

        String idempotencyKey = UUID.randomUUID().toString();
        String requestBody = """
                {
                  "amount": "10.00",
                  "reason": "Test refund"
                }
                """;

        // First request
        request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/admin/orders/" + orderId + "/refund").then().statusCode(201)
                .body("refundId", notNullValue());

        // Duplicate request with same idempotency key should return cached result
        request().header("X-Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/admin/orders/" + orderId + "/refund").then().statusCode(200)
                .body("refundId", notNullValue());
    }

    @Test
    void adminRefundOrder_shouldReturn404ForInvalidOrder() {
        UUID invalidId = UUID.randomUUID();
        String requestBody = """
                {
                  "amount": "10.00",
                  "reason": "Test refund"
                }
                """;

        request().header("X-Idempotency-Key", UUID.randomUUID().toString()).body(requestBody).when()
                .post("/api/v1/admin/orders/" + invalidId + "/refund").then().statusCode(404);
    }

    @Test
    void adminRefundOrder_withRestockQueuesInventoryTasks() {
        setupPaymentIntentTransactionally();

        String body = """
                {
                  "amount": "10.00",
                  "reason": "Test refund",
                  "restock": true
                }
                """;

        request().header("X-Idempotency-Key", UUID.randomUUID().toString()).body(body).when()
                .post("/api/v1/admin/orders/" + orderId + "/refund").then().statusCode(201)
                .body("refundId", notNullValue());

        List<ReturnInventoryTask> tasks = returnInventoryTaskQueue.snapshot();
        assertEquals(1, tasks.size());
        assertEquals(orderId, tasks.get(0).getOrderId());
    }

    // ========================================
    // POST /admin/orders/{orderId}/returns Tests
    // ========================================

    @Test
    void adminInitiateReturn_shouldCreateReturnAuthorization() {
        String requestBody = """
                {
                  "items": [
                    {
                      "lineItemId": "%s",
                      "quantity": 1,
                      "reasonCode": "defective",
                      "notes": "Item arrived damaged"
                    }
                  ]
                }
                """;

        // Get line item ID
        UUID lineItemId = entityManager
                .createQuery("SELECT oli.id FROM OrderLineItem oli WHERE oli.order.id = :orderId", UUID.class)
                .setParameter("orderId", orderId).getSingleResult();

        request().body(String.format(requestBody, lineItemId.toString())).when()
                .post("/api/v1/admin/orders/" + orderId + "/returns").then().statusCode(201)
                .body("returnId", notNullValue()).body("orderId", equalTo(orderId.toString()))
                .body("status", equalTo("requested")).body("restockDecision", equalTo("pending_inspection"));

        List<ReturnInventoryTask> inventoryTasks = returnInventoryTaskQueue.snapshot();
        assertEquals(1, inventoryTasks.size());
        assertEquals(lineItemId, inventoryTasks.get(0).getLineItemId());

        List<ReturnNotificationTask> notificationTasks = returnNotificationTaskQueue.snapshot();
        assertEquals(1, notificationTasks.size());
        assertEquals("Order Test Consignor", notificationTasks.get(0).getConsignorName());
    }

    @Test
    void adminInitiateReturn_shouldRequireItems() {
        String requestBody = """
                {
                  "items": []
                }
                """;

        request().body(requestBody).when().post("/api/v1/admin/orders/" + orderId + "/returns").then().statusCode(400)
                .body("title", equalTo("Bad Request"));
    }

    @Test
    void adminInitiateReturn_shouldReturn404ForInvalidOrder() {
        UUID invalidId = UUID.randomUUID();
        UUID lineItemId = entityManager
                .createQuery("SELECT oli.id FROM OrderLineItem oli WHERE oli.order.id = :orderId", UUID.class)
                .setParameter("orderId", orderId).getSingleResult();
        String requestBody = """
                {
                  "items": [
                    {
                      "lineItemId": "%s",
                      "quantity": 1,
                      "reasonCode": "defective"
                    }
                  ]
                }
                """;

        request().body(String.format(requestBody, lineItemId)).when()
                .post("/api/v1/admin/orders/" + invalidId + "/returns").then().statusCode(404);
    }

    // ========================================
    // Helper Methods
    // ========================================

    @Transactional
    void setupPaymentIntentTransactionally() {
        villagecompute.storefront.data.models.PaymentIntent paymentIntent = new villagecompute.storefront.data.models.PaymentIntent();
        Tenant tenant = entityManager.createQuery("SELECT t FROM Tenant t WHERE t.subdomain = :subdomain", Tenant.class)
                .setParameter("subdomain", TEST_TENANT_SUBDOMAIN).getSingleResult();

        paymentIntent.tenant = tenant;
        paymentIntent.provider = "stripe";
        paymentIntent.providerPaymentId = "pi_test_" + UUID.randomUUID();
        paymentIntent.orderId = orderId;
        paymentIntent.amount = new BigDecimal("64.24");
        paymentIntent.currency = "USD";
        paymentIntent.status = villagecompute.storefront.data.models.PaymentIntent.PaymentStatus.CAPTURED;
        paymentIntent.captureMethod = villagecompute.storefront.data.models.PaymentIntent.CaptureMethod.AUTOMATIC;
        paymentIntent.amountCaptured = new BigDecimal("64.24");
        paymentIntent.amountRefunded = BigDecimal.ZERO;
        paymentIntent.createdAt = java.time.Instant.now();
        paymentIntent.updatedAt = java.time.Instant.now();
        entityManager.persist(paymentIntent);
        entityManager.flush();

        // Link to order
        Order order = entityManager.find(Order.class, orderId);
        order.paymentIntentId = paymentIntent.providerPaymentId;
        entityManager.persist(order);
        entityManager.flush();
    }
}
