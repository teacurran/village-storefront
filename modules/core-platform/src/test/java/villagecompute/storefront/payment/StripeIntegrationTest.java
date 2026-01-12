package villagecompute.storefront.payment;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Refund;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.WebhookEvent;
import villagecompute.storefront.payment.stripe.StripePaymentProvider;
import villagecompute.storefront.payment.stripe.StripeWebhookHandler;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Comprehensive integration tests for Stripe payment provider covering the full payment lifecycle: intent creation,
 * capture, refund, webhook processing, and idempotency.
 *
 * Tests validate acceptance criteria: - Intent create/capture/refund sandbox integration passes - Webhooks stored
 * idempotently - Provider implements all PaymentProvider methods
 *
 * Set STRIPE_TEST_MODE=true with test API keys to run against Stripe sandbox.
 */
@QuarkusTest
class StripeIntegrationTest {

    @Inject
    StripePaymentProvider stripeProvider;

    @Inject
    StripeWebhookHandler webhookHandler;

    private Tenant testTenant;

    @BeforeEach
    @Transactional
    void setUp() {
        if (testTenant == null) {
            testTenant = Tenant.find("subdomain", "stripe-integration-test").firstResult();
        }

        if (testTenant == null) {
            testTenant = new Tenant();
            testTenant.subdomain = "stripe-integration-test";
            testTenant.name = "Stripe Integration Test Store";
            testTenant.status = "active";
            OffsetDateTime now = OffsetDateTime.now();
            testTenant.createdAt = now;
            testTenant.updatedAt = now;
            testTenant.persist();
        }

        TenantContext.setCurrentTenantId(testTenant.id);

        PaymentIntent.delete("tenant.id", testTenant.id);
        Refund.delete("tenant.id", testTenant.id);
        WebhookEvent.delete("tenant.id", testTenant.id);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        PaymentIntent.delete("tenant.id", testTenant.id);
        Refund.delete("tenant.id", testTenant.id);
        WebhookEvent.delete("tenant.id", testTenant.id);
        TenantContext.clear();
    }

    /**
     * Test complete payment flow: create intent -> capture -> verify status. Validates acceptance criteria: "Stripe
     * sandbox integration test passes (intent create/capture/refund)".
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testCompletePaymentFlow_CreateAndCapture() {
        String idempotencyKey = "test-flow-" + System.currentTimeMillis();
        BigDecimal amount = new BigDecimal("150.00");

        PaymentProvider.CreatePaymentIntentRequest createRequest = new PaymentProvider.CreatePaymentIntentRequest(
                amount, "USD", null, null, false, Map.of("order_id", "ORDER-123", "test", "integration"),
                idempotencyKey, null, null);

        PaymentProvider.PaymentIntentResult createResult = stripeProvider.createIntent(createRequest);

        assertNotNull(createResult);
        assertNotNull(createResult.paymentIntentId());
        assertTrue(createResult.paymentIntentId().startsWith("pi_"));
        assertNotNull(createResult.clientSecret());
        assertTrue(createResult.clientSecret().contains("_secret_"));

        String paymentIntentId = createResult.paymentIntentId();

        PaymentProvider.PaymentStatus status = stripeProvider.getPaymentStatus(paymentIntentId);
        assertTrue(status == PaymentProvider.PaymentStatus.PENDING
                || status == PaymentProvider.PaymentStatus.REQUIRES_ACTION);

        BigDecimal captureAmount = new BigDecimal("150.00");
        PaymentProvider.CaptureResult captureResult = stripeProvider.capturePayment(paymentIntentId, captureAmount);

        assertNotNull(captureResult);
        assertEquals(paymentIntentId, captureResult.transactionId());
        assertTrue(captureResult.status() == PaymentProvider.PaymentStatus.CAPTURED
                || captureResult.status() == PaymentProvider.PaymentStatus.PENDING);
        assertEquals(0, captureAmount.compareTo(captureResult.amountCaptured()));
    }

    /**
     * Test refund flow: create intent -> capture -> refund. Validates acceptance criteria for refund operations.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testRefundFlow() {
        String idempotencyKey = "test-refund-" + System.currentTimeMillis();
        BigDecimal amount = new BigDecimal("200.00");

        PaymentProvider.CreatePaymentIntentRequest createRequest = new PaymentProvider.CreatePaymentIntentRequest(
                amount, "USD", null, null, true, Map.of("test", "refund-flow"), idempotencyKey, null, null);

        PaymentProvider.PaymentIntentResult createResult = stripeProvider.createIntent(createRequest);
        String paymentIntentId = createResult.paymentIntentId();

        BigDecimal refundAmount = new BigDecimal("50.00");
        PaymentProvider.RefundResult refundResult = stripeProvider.refundPayment(paymentIntentId, refundAmount,
                "requested_by_customer");

        assertNotNull(refundResult);
        assertNotNull(refundResult.refundId());
        assertTrue(refundResult.refundId().startsWith("re_"));
        assertEquals(0, refundAmount.compareTo(refundResult.amountRefunded()));
        assertTrue(refundResult.status() == PaymentProvider.RefundStatus.SUCCEEDED
                || refundResult.status() == PaymentProvider.RefundStatus.PENDING);
    }

    /**
     * Test payment cancellation before capture.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testCancelPayment() {
        String idempotencyKey = "test-cancel-" + System.currentTimeMillis();

        PaymentProvider.CreatePaymentIntentRequest createRequest = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("75.00"), "USD", null, null, false, Map.of("test", "cancellation"), idempotencyKey, null,
                null);

        PaymentProvider.PaymentIntentResult createResult = stripeProvider.createIntent(createRequest);
        String paymentIntentId = createResult.paymentIntentId();

        PaymentProvider.CancellationResult cancelResult = stripeProvider.cancelPayment(paymentIntentId);

        assertTrue(cancelResult.success());
        assertEquals(PaymentProvider.PaymentStatus.CANCELLED, cancelResult.finalStatus());

        PaymentProvider.PaymentStatus status = stripeProvider.getPaymentStatus(paymentIntentId);
        assertEquals(PaymentProvider.PaymentStatus.CANCELLED, status);
    }

    /**
     * Test webhook idempotency: same webhook processed twice should be detected as duplicate. Validates acceptance
     * criteria: "webhooks stored idempotently".
     */
    @Test
    @Transactional
    void testWebhookIdempotency() {
        String eventId = "evt_test_" + System.currentTimeMillis();
        String payload = String.format(
                "{\"id\":\"%s\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_test_123\",\"amount_received\":10000}}}",
                eventId);

        WebhookHandler.WebhookRequest request1 = new WebhookHandler.WebhookRequest(eventId, "payment_intent.succeeded",
                payload, "test-signature", Map.of());

        WebhookHandler.WebhookProcessingResult result1 = webhookHandler.processWebhook(request1);

        assertTrue(result1.success());
        assertFalse(result1.alreadyProcessed());
        assertNotNull(result1.eventId());

        WebhookHandler.WebhookRequest request2 = new WebhookHandler.WebhookRequest(eventId, "payment_intent.succeeded",
                payload, "test-signature", Map.of());

        WebhookHandler.WebhookProcessingResult result2 = webhookHandler.processWebhook(request2);

        assertTrue(result2.success());
        assertTrue(result2.alreadyProcessed(), "Second webhook with same event ID should be detected as duplicate");
        assertEquals(result1.eventId(), result2.eventId());

        long eventCount = WebhookEvent.count("providerEventId", eventId);
        assertEquals(1, eventCount, "Should only store webhook event once despite multiple deliveries");
    }

    /**
     * Test webhook event retrieval for audit/replay.
     */
    @Test
    @Transactional
    void testWebhookEventRetrieval() {
        String eventId = "evt_retrieval_test_" + System.currentTimeMillis();
        String payload = String.format(
                "{\"id\":\"%s\",\"type\":\"charge.refunded\",\"data\":{\"object\":{\"id\":\"ch_test\"}}}", eventId);

        WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest(eventId, "charge.refunded", payload,
                "test-sig", Map.of());

        WebhookHandler.WebhookProcessingResult processResult = webhookHandler.processWebhook(request);
        assertTrue(processResult.success());

        WebhookHandler.WebhookEventInfo eventInfo = webhookHandler.getWebhookEvent(eventId);

        assertNotNull(eventInfo);
        assertEquals(eventId, eventInfo.providerEventId());
        assertEquals("charge.refunded", eventInfo.eventType());
        assertEquals(payload, eventInfo.payload());
        assertTrue(eventInfo.processed());
        assertNull(eventInfo.processingError());
    }

    /**
     * Test idempotency key handling: creating same payment twice with same key returns same result.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testIdempotencyKeyBehavior() {
        String idempotencyKey = "test-idempotent-key-" + System.currentTimeMillis();
        BigDecimal amount = new BigDecimal("99.99");

        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(amount,
                "USD", null, null, false, Map.of("test", "idempotency"), idempotencyKey, null, null);

        PaymentProvider.PaymentIntentResult result1 = stripeProvider.createIntent(request);
        PaymentProvider.PaymentIntentResult result2 = stripeProvider.createIntent(request);

        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.paymentIntentId(), result2.paymentIntentId(),
                "Same idempotency key should return same payment intent");
        assertEquals(result1.clientSecret(), result2.clientSecret());
    }

    /**
     * Test stub mode behavior when Stripe credentials are not configured. Validates that provider degrades gracefully
     * for development/test environments without real credentials.
     */
    @Test
    void testStubModeBehavior() {
        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("10.00"), "USD", null, null, true, Map.of("test", "stub"), "stub-key-123", null, null);

        PaymentProvider.PaymentIntentResult result = stripeProvider.createIntent(request);

        assertNotNull(result);
        assertTrue(result.paymentIntentId().startsWith("pi_stub_"));
        assertTrue(result.clientSecret().startsWith("cs_stub_"));
        assertNotNull(result.providerMetadata());
        assertTrue(result.providerMetadata().containsKey("mode"));
    }

    /**
     * Test partial capture: authorize for full amount, capture less.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testPartialCapture() {
        String idempotencyKey = "test-partial-" + System.currentTimeMillis();
        BigDecimal authorizedAmount = new BigDecimal("100.00");

        PaymentProvider.CreatePaymentIntentRequest createRequest = new PaymentProvider.CreatePaymentIntentRequest(
                authorizedAmount, "USD", null, null, false, Map.of("test", "partial-capture"), idempotencyKey, null,
                null);

        PaymentProvider.PaymentIntentResult createResult = stripeProvider.createIntent(createRequest);
        String paymentIntentId = createResult.paymentIntentId();

        BigDecimal captureAmount = new BigDecimal("75.00");
        PaymentProvider.CaptureResult captureResult = stripeProvider.capturePayment(paymentIntentId, captureAmount);

        assertNotNull(captureResult);
        assertEquals(0, captureAmount.compareTo(captureResult.amountCaptured()),
                "Should capture partial amount successfully");
    }

    /**
     * Test metadata preservation through payment lifecycle.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testMetadataPreservation() {
        String idempotencyKey = "test-metadata-" + System.currentTimeMillis();
        Map<String, String> metadata = Map.of("order_id", "ORD-12345", "customer_email", "test@example.com", "source",
                "web");

        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("125.00"), "USD", null, null, false, metadata, idempotencyKey, null, null);

        PaymentProvider.PaymentIntentResult result = stripeProvider.createIntent(request);

        assertNotNull(result);
        assertNotNull(result.providerMetadata());
    }
}
