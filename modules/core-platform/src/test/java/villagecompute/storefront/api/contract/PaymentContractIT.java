package villagecompute.storefront.api.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.atlassian.oai.validator.restassured.OpenApiValidationFilter;

import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.testsupport.PostgresTenantTestResource;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * REST-assured contract tests for payment APIs and Stripe webhook handling.
 *
 * <p>
 * These tests validate:
 * <ul>
 * <li>Stripe webhook flows update order/payment state</li>
 * <li>Webhook idempotency (duplicate events are ignored)</li>
 * <li>Webhook health endpoint availability</li>
 * <li>Admin refund endpoint contract</li>
 * </ul>
 * </p>
 */
@QuarkusTest
@QuarkusTestResource(
        value = PostgresTenantTestResource.class,
        restrictToAnnotatedClass = true)
class PaymentContractIT {

    private static final String API_BASE_PATH = "/api/v1";
    private static final String WEBHOOK_PATH = "/api/webhooks/payments/stripe";
    private static final String TECHGADGETS_HOST = "techgadgets.villagecompute.com";
    private static final OpenApiValidationFilter PAYMENT_OPENAPI_VALIDATOR = buildOpenApiValidator();

    @Inject
    EntityManager entityManager;

    private Tenant techGadgetsTenant;
    private Order testOrder;
    private PaymentIntent testPaymentIntent;

    @BeforeEach
    @Transactional
    void setUp() {
        cleanDatabase();
        String paymentIntentId = "pi_contract_" + UUID.randomUUID();
        techGadgetsTenant = createTenant(UUID.fromString("a0000000-0000-0000-0000-000000000001"), "techgadgets",
                "Tech Gadgets Online");
        testOrder = createOrder(techGadgetsTenant, "ORD-CONTRACT-001", new BigDecimal("149.99"), paymentIntentId);
        testPaymentIntent = createPaymentIntent(techGadgetsTenant, testOrder, paymentIntentId,
                new BigDecimal("149.99"));
        entityManager.flush();
        entityManager.clear();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void webhookPaymentSucceeded_UpdatesOrderAndPaymentIntent() {
        Map<String, Object> payload = createStripeWebhookPayload("payment_intent.succeeded",
                testPaymentIntent.providerPaymentId, testOrder.id, testPaymentIntent.amount);

        webhookRequest().body(payload).when().post(WEBHOOK_PATH).then().statusCode(200).body("received", equalTo(true))
                .body("alreadyProcessed", equalTo(false));

        Order updatedOrder = reloadOrder(testOrder.id);
        PaymentIntent updatedIntent = reloadPaymentIntent(testPaymentIntent.id);

        assertEquals(Order.OrderStatus.PAID, updatedOrder.status);
        assertEquals(PaymentIntent.PaymentStatus.CAPTURED, updatedIntent.status);
        assertEquals(0, updatedIntent.amountCaptured.compareTo(testPaymentIntent.amount));
    }

    @Test
    void webhookPaymentFailed_RevertsOrderToPending() {
        // Simulate order progressing before failure to verify rollback
        Order order = entityManager.find(Order.class, testOrder.id);
        order.status = Order.OrderStatus.PROCESSING;
        entityManager.flush();

        Map<String, Object> payload = createStripeWebhookPayload("payment_intent.payment_failed",
                testPaymentIntent.providerPaymentId, testOrder.id, testPaymentIntent.amount);

        webhookRequest().body(payload).when().post(WEBHOOK_PATH).then().statusCode(200).body("received", equalTo(true));

        Order updatedOrder = reloadOrder(testOrder.id);
        PaymentIntent updatedIntent = reloadPaymentIntent(testPaymentIntent.id);

        assertEquals(Order.OrderStatus.PENDING_PAYMENT, updatedOrder.status);
        assertEquals(PaymentIntent.PaymentStatus.FAILED, updatedIntent.status);
    }

    @Test
    void webhookIdempotency_DuplicateEvent_ReturnsAlreadyProcessed() {
        Map<String, Object> payload = createStripeWebhookPayload("payment_intent.succeeded",
                testPaymentIntent.providerPaymentId, testOrder.id, testPaymentIntent.amount);
        String eventId = "evt_contract_" + UUID.randomUUID();
        payload.put("id", eventId);

        webhookRequest().body(payload).when().post(WEBHOOK_PATH).then().statusCode(200).body("alreadyProcessed",
                equalTo(false));

        webhookRequest().body(payload).when().post(WEBHOOK_PATH).then().statusCode(200).body("alreadyProcessed",
                equalTo(true));

        Long webhookCount = entityManager.createQuery("SELECT COUNT(w) FROM WebhookEvent w", Long.class)
                .getSingleResult();
        assertEquals(1L, webhookCount.longValue());
    }

    @Test
    void webhookMissingSignature_Returns400() {
        Map<String, Object> payload = createStripeWebhookPayload("payment_intent.succeeded",
                testPaymentIntent.providerPaymentId, testOrder.id, testPaymentIntent.amount);

        given().filter(PAYMENT_OPENAPI_VALIDATOR).header("Host", TECHGADGETS_HOST).accept(ContentType.JSON)
                .contentType(ContentType.JSON).body(payload).when().post(WEBHOOK_PATH).then().statusCode(400)
                .body("error", equalTo("Missing Stripe-Signature header"));
    }

    @Test
    void webhookRefundSucceeded_UpdatesOrderStatus() {
        PaymentIntent intent = entityManager.find(PaymentIntent.class, testPaymentIntent.id);
        intent.status = PaymentIntent.PaymentStatus.CAPTURED;
        intent.amountCaptured = testPaymentIntent.amount;
        entityManager.flush();

        Order order = entityManager.find(Order.class, testOrder.id);
        order.status = Order.OrderStatus.PAID;
        entityManager.flush();

        Map<String, Object> payload = createStripeWebhookPayload("charge.refunded", testPaymentIntent.providerPaymentId,
                testOrder.id, testPaymentIntent.amount);

        webhookRequest().body(payload).when().post(WEBHOOK_PATH).then().statusCode(200).body("received", equalTo(true));

        Order updatedOrder = reloadOrder(testOrder.id);
        PaymentIntent updatedIntent = reloadPaymentIntent(testPaymentIntent.id);

        assertEquals(Order.OrderStatus.REFUNDED, updatedOrder.status);
        assertEquals(0, updatedIntent.amountRefunded.compareTo(testPaymentIntent.amount));
    }

    @Test
    void webhookChargeDisputed_MarksPaymentDisputed() {
        Map<String, Object> payload = createStripeWebhookPayload("charge.dispute.created",
                testPaymentIntent.providerPaymentId, testOrder.id, testPaymentIntent.amount);

        webhookRequest().body(payload).when().post(WEBHOOK_PATH).then().statusCode(200).body("received", equalTo(true));

        PaymentIntent updatedIntent = reloadPaymentIntent(testPaymentIntent.id);
        assertEquals(PaymentIntent.PaymentStatus.DISPUTED, updatedIntent.status);
    }

    @Test
    void webhookHealthEndpoint_ReturnsHealthyStatus() {
        given().filter(PAYMENT_OPENAPI_VALIDATOR).header("Host", TECHGADGETS_HOST).when()
                .get("/api/webhooks/payments/health").then().statusCode(200).body("status", equalTo("healthy"))
                .body("providers", notNullValue());
    }

    @Test
    void createRefund_RequiresIdempotencyKey() {
        Map<String, Object> refundRequest = new HashMap<>();
        refundRequest.put("amount", "50.00");
        refundRequest.put("reason", "customer_request");

        adminRequest().body(refundRequest).when().post(API_BASE_PATH + "/admin/orders/" + testOrder.id + "/refund")
                .then().statusCode(400).body("title", equalTo("Bad Request"));
    }

    @Test
    void createRefund_ReturnsPendingResponse() {
        Map<String, Object> refundRequest = new HashMap<>();
        refundRequest.put("amount", "25.00");
        refundRequest.put("reason", "partial_refund");

        adminRequest().header("X-Idempotency-Key", UUID.randomUUID().toString()).body(refundRequest).when()
                .post(API_BASE_PATH + "/admin/orders/" + testOrder.id + "/refund").then().statusCode(201)
                .body("refundId", notNullValue()).body("status", equalTo("pending")).body("amount", equalTo("25.00"));
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

        throw new IllegalStateException("OpenAPI spec not found for payment contract tests");
    }

    private RequestSpecification webhookRequest() {
        return given().filter(PAYMENT_OPENAPI_VALIDATOR).header("Host", TECHGADGETS_HOST)
                .header("Stripe-Signature", generateStripeSignature()).accept(ContentType.JSON)
                .contentType(ContentType.JSON);
    }

    private RequestSpecification adminRequest() {
        return given().filter(PAYMENT_OPENAPI_VALIDATOR).header("Host", TECHGADGETS_HOST)
                .header("Authorization", "Bearer test-admin-token").accept(ContentType.JSON)
                .contentType(ContentType.JSON);
    }

    private String generateStripeSignature() {
        return "t=1234567890,v1=mocksignature";
    }

    private Map<String, Object> createStripeWebhookPayload(String eventType, String paymentIntentId, UUID orderId,
            BigDecimal amount) {

        Map<String, Object> payload = new HashMap<>();
        payload.put("id", "evt_contract_" + UUID.randomUUID());
        payload.put("type", eventType);
        payload.put("created", System.currentTimeMillis() / 1000);

        Map<String, Object> data = new HashMap<>();
        Map<String, Object> object = new HashMap<>();
        object.put("currency", "usd");

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("orderId", orderId.toString());
        object.put("metadata", metadata);

        if ("charge.refunded".equals(eventType) || "charge.dispute.created".equals(eventType)) {
            object.put("id", "ch_" + UUID.randomUUID());
            object.put("payment_intent", paymentIntentId);
            if ("charge.refunded".equals(eventType)) {
                object.put("amount_refunded", amountToCents(amount));
            }
        } else {
            object.put("id", paymentIntentId);
            object.put("amount", amountToCents(amount));
            object.put("amount_received", amountToCents(amount));
        }

        data.put("object", object);
        payload.put("data", data);
        return payload;
    }

    private int amountToCents(BigDecimal amount) {
        return amount.multiply(new BigDecimal("100")).intValue();
    }

    private void cleanDatabase() {
        entityManager.createQuery("DELETE FROM WebhookEvent").executeUpdate();
        entityManager.createQuery("DELETE FROM PaymentIntent").executeUpdate();
        entityManager.createQuery("DELETE FROM Order").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }

    private Tenant createTenant(UUID id, String subdomain, String name) {
        Tenant tenant = new Tenant();
        tenant.id = id;
        tenant.subdomain = subdomain;
        tenant.name = name;
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        entityManager.persist(tenant);
        return tenant;
    }

    private Order createOrder(Tenant tenant, String orderNumber, BigDecimal total, String paymentIntentId) {
        Order order = new Order();
        order.id = UUID.randomUUID();
        order.tenant = tenant;
        order.orderNumber = orderNumber;
        order.customerEmail = "checkout@example.com";
        order.shippingAddress = """
                {"line1":"123 Main","city":"Austin","state":"TX","postalCode":"73301","country":"US"}
                """;
        order.billingAddress = order.shippingAddress;
        order.subtotalAmount = total;
        order.discountAmount = BigDecimal.ZERO;
        order.shippingAmount = new BigDecimal("10.00");
        order.taxAmount = new BigDecimal("12.00");
        order.totalAmount = total;
        order.currency = "USD";
        order.status = Order.OrderStatus.PENDING_PAYMENT;
        order.paymentIntentId = paymentIntentId;
        entityManager.persist(order);
        return order;
    }

    private PaymentIntent createPaymentIntent(Tenant tenant, Order order, String providerPaymentId, BigDecimal amount) {
        PaymentIntent intent = new PaymentIntent();
        intent.tenant = tenant;
        intent.provider = "stripe";
        intent.providerPaymentId = providerPaymentId;
        intent.orderId = order.id;
        intent.amount = amount;
        intent.currency = "USD";
        intent.status = PaymentIntent.PaymentStatus.PENDING;
        intent.captureMethod = PaymentIntent.CaptureMethod.AUTOMATIC;
        intent.amountCaptured = BigDecimal.ZERO;
        intent.amountRefunded = BigDecimal.ZERO;
        intent.createdAt = Instant.now();
        intent.updatedAt = intent.createdAt;
        entityManager.persist(intent);
        return intent;
    }

    private Order reloadOrder(UUID orderId) {
        entityManager.clear();
        return entityManager.find(Order.class, orderId);
    }

    private PaymentIntent reloadPaymentIntent(Long paymentIntentId) {
        entityManager.clear();
        return entityManager.find(PaymentIntent.class, paymentIntentId);
    }
}
