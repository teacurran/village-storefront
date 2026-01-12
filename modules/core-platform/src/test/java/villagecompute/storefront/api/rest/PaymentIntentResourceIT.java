package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.PaymentService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for PaymentIntentResource REST endpoints. Tests payment intent creation, capture, refund, and
 * retrieval operations with idempotency and error handling.
 */
@QuarkusTest
class PaymentIntentResourceIT {

    @Inject
    PaymentService paymentService;

    private Tenant testTenant;

    @BeforeEach
    @Transactional
    void setUp() {
        if (testTenant == null) {
            testTenant = Tenant.find("subdomain", "payment-intent-test").firstResult();
        }

        if (testTenant == null) {
            testTenant = new Tenant();
            testTenant.subdomain = "payment-intent-test";
            testTenant.name = "Payment Intent Test";
            testTenant.status = "active";
            OffsetDateTime now = OffsetDateTime.now();
            testTenant.createdAt = now;
            testTenant.updatedAt = now;
            testTenant.persist();
        }

        TenantContext.setCurrentTenantId(testTenant.id);

        // Clean up test data
        PaymentIntent.find("tenant.id", testTenant.id).list().forEach(pi -> ((PaymentIntent) pi).delete());
    }

    @AfterEach
    @Transactional
    void tearDown() {
        PaymentIntent.find("tenant.id", testTenant.id).list().forEach(pi -> ((PaymentIntent) pi).delete());
        TenantContext.clear();
    }

    /**
     * Test creating a payment intent with automatic capture.
     */
    @Test
    void testCreatePaymentIntent_Success() {
        String idempotencyKey = "test-create-" + System.currentTimeMillis();
        String requestBody = """
                {
                  "amount": 100.00,
                  "currency": "USD",
                  "captureImmediately": true,
                  "idempotencyKey": "test-create-%d"
                }
                """.formatted(System.currentTimeMillis());

        given().contentType(ContentType.JSON).header("Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/payments/intents").then().statusCode(201).body("id", notNullValue())
                .body("providerPaymentId", startsWith("pi_")).body("clientSecret", containsString("_secret_"))
                .body("status", notNullValue()).body("amount", equalTo(100.00f)).body("currency", equalTo("USD"))
                .body("metadata.platform_fee_amount", notNullValue());
    }

    /**
     * Test creating payment intent with order association.
     */
    @Test
    void testCreatePaymentIntent_WithOrderId() {
        UUID orderId = UUID.randomUUID();
        String idempotencyKey = "test-order-" + System.currentTimeMillis();
        String requestBody = """
                {
                  "amount": 50.00,
                  "currency": "USD",
                  "orderId": "%s",
                  "captureImmediately": false,
                  "idempotencyKey": "test-order-%d"
                }
                """.formatted(orderId, System.currentTimeMillis());

        given().contentType(ContentType.JSON).header("Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/payments/intents").then().statusCode(201).body("orderId", equalTo(orderId.toString()))
                .body("metadata.order_id", equalTo(orderId.toString()));
    }

    /**
     * Test idempotency key handling - duplicate request returns existing payment intent.
     */
    @Test
    @Transactional
    void testCreatePaymentIntent_Idempotency() {
        String idempotencyKey = "test-idempotent-" + System.currentTimeMillis();

        // Create first payment intent
        PaymentIntent first = paymentService.createPaymentIntent(new BigDecimal("25.00"), "USD", null, true,
                idempotencyKey);

        assertNotNull(first);

        // Second request with same idempotency key should return same intent
        PaymentIntent second = paymentService.createPaymentIntent(new BigDecimal("25.00"), "USD", null, true,
                idempotencyKey);

        assertNotNull(second);
        assertEquals(first.id, second.id);
        assertEquals(first.providerPaymentId, second.providerPaymentId);
    }

    /**
     * Test invalid request - missing required fields.
     */
    @Test
    void testCreatePaymentIntent_InvalidRequest() {
        String idempotencyKey = "invalid-request-" + System.currentTimeMillis();
        String requestBody = """
                {
                  "currency": "USD"
                }
                """;

        given().contentType(ContentType.JSON).header("Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/payments/intents").then().statusCode(400);
    }

    /**
     * Test invalid amount (negative).
     */
    @Test
    void testCreatePaymentIntent_NegativeAmount() {
        String idempotencyKey = "negative-amount-" + System.currentTimeMillis();
        String requestBody = """
                {
                  "amount": -10.00,
                  "currency": "USD",
                  "captureImmediately": true
                }
                """;

        given().contentType(ContentType.JSON).header("Idempotency-Key", idempotencyKey).body(requestBody).when()
                .post("/api/v1/payments/intents").then().statusCode(400);
    }

    /**
     * Test capturing a payment intent.
     */
    @Test
    @Transactional
    void testCapturePayment_Success() {
        // Create payment intent with manual capture
        PaymentIntent paymentIntent = createManualCaptureIntent();

        // Simulate webhook updating status to AUTHORIZED
        paymentIntent.status = PaymentIntent.PaymentStatus.AUTHORIZED;

        String requestBody = "{}";

        given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/payments/intents/{id}/capture", paymentIntent.id).then().statusCode(200)
                .body("id", equalTo(paymentIntent.id.intValue()));
    }

    /**
     * Test capturing payment that is not in AUTHORIZED state fails.
     */
    @Test
    @Transactional
    void testCapturePayment_InvalidState() {
        PaymentIntent paymentIntent = createManualCaptureIntent();
        paymentIntent.status = PaymentIntent.PaymentStatus.PENDING; // Not authorized yet

        String requestBody = "{}";

        given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/payments/intents/{id}/capture", paymentIntent.id).then().statusCode(400);
    }

    /**
     * Test capturing non-existent payment returns 404.
     */
    @Test
    void testCapturePayment_NotFound() {
        given().contentType(ContentType.JSON).body("{}").when().post("/api/v1/payments/intents/999999/capture").then()
                .statusCode(404);
    }

    /**
     * Test refunding a payment.
     */
    @Test
    @Transactional
    void testRefundPayment_Success() {
        PaymentIntent paymentIntent = createCapturedIntent();

        String requestBody = """
                {
                  "amountToRefund": 50.00,
                  "reason": "requested_by_customer"
                }
                """;

        given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/payments/intents/{id}/refund", paymentIntent.id).then().statusCode(200)
                .body("id", equalTo(paymentIntent.id.intValue()));
    }

    /**
     * Test refunding payment that is not captured fails.
     */
    @Test
    @Transactional
    void testRefundPayment_InvalidState() {
        PaymentIntent paymentIntent = createManualCaptureIntent();
        paymentIntent.status = PaymentIntent.PaymentStatus.PENDING;

        String requestBody = """
                {
                  "amountToRefund": 10.00,
                  "reason": "duplicate"
                }
                """;

        given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/payments/intents/{id}/refund", paymentIntent.id).then().statusCode(400);
    }

    /**
     * Test refund request missing amount.
     */
    @Test
    @Transactional
    void testRefundPayment_MissingAmount() {
        PaymentIntent paymentIntent = createCapturedIntent();

        String requestBody = """
                {
                  "reason": "fraudulent"
                }
                """;

        given().contentType(ContentType.JSON).body(requestBody).when()
                .post("/api/v1/payments/intents/{id}/refund", paymentIntent.id).then().statusCode(400);
    }

    /**
     * Test retrieving payment intent by ID.
     */
    @Test
    @Transactional
    void testGetPaymentIntent_Success() {
        PaymentIntent paymentIntent = createManualCaptureIntent();

        given().when().get("/api/v1/payments/intents/{id}", paymentIntent.id).then().statusCode(200)
                .body("id", equalTo(paymentIntent.id.intValue()))
                .body("providerPaymentId", equalTo(paymentIntent.providerPaymentId)).body("clientSecret", nullValue()) // Should
                                                                                                                       // not
                                                                                                                       // expose
                                                                                                                       // client
                                                                                                                       // secret
                                                                                                                       // on
                                                                                                                       // retrieval
                .body("status", equalTo(paymentIntent.status.name()));
    }

    /**
     * Test retrieving non-existent payment intent returns 404.
     */
    @Test
    void testGetPaymentIntent_NotFound() {
        given().when().get("/api/v1/payments/intents/999999").then().statusCode(404);
    }

    /**
     * Test full payment lifecycle: create -> capture -> refund.
     */
    @Test
    @Transactional
    void testPaymentLifecycle() {
        // 1. Create payment intent with manual capture
        PaymentIntent created = paymentService.createPaymentIntent(new BigDecimal("100.00"), "USD", null, false,
                "lifecycle-test-" + System.currentTimeMillis());

        assertNotNull(created);
        assertEquals(PaymentIntent.PaymentStatus.PENDING, created.status);

        // 2. Simulate authorization (would come from Stripe webhook)
        created.status = PaymentIntent.PaymentStatus.AUTHORIZED;

        // 3. Capture payment
        PaymentIntent captured = paymentService.capturePayment(created.id, null);
        assertNotNull(captured);

        // 4. Refund half the amount
        PaymentIntent refunded = paymentService.refundPayment(captured.id, new BigDecimal("50.00"),
                "requested_by_customer");
        assertNotNull(refunded);
        assertEquals(0, new BigDecimal("50.00").compareTo(refunded.amountRefunded));
    }

    // Helper methods

    private PaymentIntent createManualCaptureIntent() {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.tenant = testTenant;
        paymentIntent.provider = "stripe";
        paymentIntent.providerPaymentId = "pi_test_" + System.currentTimeMillis();
        paymentIntent.amount = new BigDecimal("100.00");
        paymentIntent.currency = "USD";
        paymentIntent.status = PaymentIntent.PaymentStatus.PENDING;
        paymentIntent.captureMethod = PaymentIntent.CaptureMethod.MANUAL;
        paymentIntent.clientSecret = "cs_test_" + System.currentTimeMillis();
        paymentIntent.createdAt = Instant.now();
        paymentIntent.updatedAt = Instant.now();
        paymentIntent.persist();
        return paymentIntent;
    }

    private PaymentIntent createCapturedIntent() {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.tenant = testTenant;
        paymentIntent.provider = "stripe";
        paymentIntent.providerPaymentId = "pi_captured_" + System.currentTimeMillis();
        paymentIntent.amount = new BigDecimal("100.00");
        paymentIntent.currency = "USD";
        paymentIntent.status = PaymentIntent.PaymentStatus.CAPTURED;
        paymentIntent.captureMethod = PaymentIntent.CaptureMethod.AUTOMATIC;
        paymentIntent.amountCaptured = new BigDecimal("100.00");
        paymentIntent.createdAt = Instant.now();
        paymentIntent.updatedAt = Instant.now();
        paymentIntent.persist();
        return paymentIntent;
    }
}
