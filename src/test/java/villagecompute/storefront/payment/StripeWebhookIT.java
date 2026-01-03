package villagecompute.storefront.payment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.ConnectAccount;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.WebhookEvent;
import villagecompute.storefront.payment.stripe.StripeWebhookHandler;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Integration tests for Stripe webhook handling. Tests webhook ingestion, signature verification, idempotency, and
 * event processing.
 */
@QuarkusTest
class StripeWebhookIT {

    @Inject
    StripeWebhookHandler webhookHandler;

    @Inject
    EntityManager entityManager;

    private Tenant testTenant;
    private Consignor testConsignor;

    @BeforeEach
    @Transactional
    void setUp() {
        if (testTenant == null) {
            testTenant = new Tenant();
            testTenant.subdomain = "webhook-test";
            testTenant.name = "Webhook Test Tenant";
            testTenant.status = "active";
            OffsetDateTime now = OffsetDateTime.now();
            testTenant.createdAt = now;
            testTenant.updatedAt = now;
            testTenant.persist();
        }

        if (testConsignor == null) {
            testConsignor = new Consignor();
            testConsignor.tenant = testTenant;
            testConsignor.name = "Consignor Webhook";
            testConsignor.contactInfo = "{}";
            testConsignor.payoutSettings = "{}";
            testConsignor.createdAt = OffsetDateTime.now();
            testConsignor.updatedAt = testConsignor.createdAt;
            testConsignor.persist();
        }

        TenantContext.setCurrentTenantId(testTenant.id);
        entityManager.createQuery("DELETE FROM WebhookEvent WHERE tenant = :tenant").setParameter("tenant", testTenant)
                .executeUpdate();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        entityManager.createQuery("DELETE FROM WebhookEvent WHERE tenant = :tenant").setParameter("tenant", testTenant)
                .executeUpdate();
        TenantContext.clear();
    }

    /**
     * Test webhook endpoint returns 400 for missing signature header.
     */
    @Test
    void testWebhookEndpoint_MissingSignature() {
        String payload = createPaymentIntentPayload("evt_missing_sig", "payment_intent.succeeded", "pi_missing_sig");

        given().contentType(ContentType.JSON).body(payload).when().post("/api/webhooks/payments/stripe").then()
                .statusCode(400).body("error", equalTo("Missing Stripe-Signature header"));
    }

    /**
     * Test webhook event persistence and idempotency handling.
     */
    @Test
    @Transactional
    void testWebhookIdempotency() {
        String eventId = "evt_idempotency_" + System.currentTimeMillis();
        String payload = createPaymentIntentPayload(eventId, "payment_intent.succeeded", "pi_idempotency");

        WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest(eventId, "payment_intent.succeeded",
                payload, "test-signature", Map.of());

        WebhookHandler.WebhookProcessingResult first = webhookHandler.processWebhook(request);
        WebhookHandler.WebhookProcessingResult second = webhookHandler.processWebhook(request);

        assertTrue(first.success());
        assertFalse(first.alreadyProcessed());
        assertTrue(second.success());
        assertTrue(second.alreadyProcessed());

        assertEquals(1, WebhookEvent.count("providerEventId", eventId));
    }

    /**
     * Test webhook event retrieval endpoint.
     */
    @Test
    @Transactional
    void testGetWebhookEvent() {
        String eventId = "evt_get_" + System.currentTimeMillis();
        WebhookEvent event = new WebhookEvent();
        event.provider = "stripe";
        event.providerEventId = eventId;
        event.eventType = "payout.paid";
        event.payload = "{}";
        event.receivedAt = Instant.now();
        event.processed = true;
        event.processedAt = Instant.now();
        event.persist();

        WebhookHandler.WebhookEventInfo info = webhookHandler.getWebhookEvent(eventId);
        assertNotNull(info);
        assertEquals(eventId, info.providerEventId());
        assertTrue(info.processed());
    }

    /**
     * Test payment_intent.succeeded webhook updates local payment intent.
     */
    @Test
    @Transactional
    void testPaymentIntentSucceededWebhookUpdatesEntity() {
        PaymentIntent paymentIntent = createPaymentIntent("pi_webhook_123");

        WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest("evt_pi_success",
                "payment_intent.succeeded", createPaymentIntentPayload("evt_pi_success", "payment_intent.succeeded",
                        paymentIntent.providerPaymentId),
                "test-signature", Map.of());

        WebhookHandler.WebhookProcessingResult result = webhookHandler.processWebhook(request);
        assertTrue(result.success());

        PaymentIntent updated = PaymentIntent.findById(paymentIntent.id);
        assertEquals(PaymentIntent.PaymentStatus.CAPTURED, updated.status);
        assertEquals(0, new BigDecimal("100.00").compareTo(updated.amountCaptured));
    }

    /**
     * Test payout webhook updates payout batch.
     */
    @Test
    @Transactional
    void testPayoutPaidWebhookUpdatesBatch() {
        PayoutBatch batch = createPayoutBatch("po_test_123");

        WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest("evt_payout_paid", "payout.paid",
                createPayoutPayload("evt_payout_paid", "po_test_123", "paid"), "test-signature", Map.of());

        WebhookHandler.WebhookProcessingResult result = webhookHandler.processWebhook(request);
        assertTrue(result.success());

        PayoutBatch updated = PayoutBatch.findById(batch.id);
        assertEquals("completed", updated.status);
        assertNotNull(updated.processedAt);
    }

    /**
     * Test account.updated webhook creates/updates connected account.
     */
    @Test
    @Transactional
    void testAccountUpdatedWebhook() {
        WebhookHandler.WebhookRequest request = new WebhookHandler.WebhookRequest("evt_account_updated",
                "account.updated", createAccountPayload("acct_test_123"), "test-signature", Map.of());

        WebhookHandler.WebhookProcessingResult result = webhookHandler.processWebhook(request);
        assertTrue(result.success());

        ConnectAccount account = ConnectAccount.findByProviderAccountId(testTenant.id, "acct_test_123");
        assertNotNull(account);
        assertTrue(account.payoutsEnabled);
        assertEquals(ConnectAccount.OnboardingStatus.COMPLETED, account.onboardingStatus);
    }

    /**
     * Test webhook health endpoint.
     */
    @Test
    void testWebhookHealthCheck() {
        given().when().get("/api/webhooks/payments/health").then().statusCode(200).body("status", equalTo("healthy"))
                .body("providers", hasItemInArray("stripe"));
    }

    private PaymentIntent createPaymentIntent(String providerPaymentId) {
        PaymentIntent paymentIntent = new PaymentIntent();
        paymentIntent.tenant = testTenant;
        paymentIntent.provider = "stripe";
        paymentIntent.providerPaymentId = providerPaymentId;
        paymentIntent.orderId = 1L;
        paymentIntent.amount = new BigDecimal("100.00");
        paymentIntent.currency = "USD";
        paymentIntent.status = PaymentIntent.PaymentStatus.PENDING;
        paymentIntent.captureMethod = PaymentIntent.CaptureMethod.AUTOMATIC;
        paymentIntent.createdAt = Instant.now();
        paymentIntent.updatedAt = Instant.now();
        paymentIntent.persist();
        return paymentIntent;
    }

    private PayoutBatch createPayoutBatch(String payoutId) {
        PayoutBatch batch = new PayoutBatch();
        batch.tenant = testTenant;
        batch.consignor = testConsignor;
        batch.periodStart = OffsetDateTime.now().minusDays(7).toLocalDate();
        batch.periodEnd = OffsetDateTime.now().toLocalDate();
        batch.totalAmount = new BigDecimal("150.00");
        batch.currency = "USD";
        batch.status = "processing";
        batch.paymentReference = payoutId;
        batch.persist();
        return batch;
    }

    private String createPaymentIntentPayload(String eventId, String eventType, String paymentIntentId) {
        return String.format("""
                {
                  "id": "%s",
                  "object": "event",
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "amount_received": 10000,
                      "currency": "usd"
                    }
                  }
                }
                """, eventId, eventType, paymentIntentId);
    }

    private String createPayoutPayload(String eventId, String payoutId, String status) {
        return String.format("""
                {
                  "id": "%s",
                  "object": "event",
                  "type": "payout.%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "status": "%s"
                    }
                  }
                }
                """, eventId, status, payoutId, status);
    }

    private String createAccountPayload(String accountId) {
        return String.format("""
                {
                  "id": "evt_account",
                  "object": "event",
                  "type": "account.updated",
                  "data": {
                    "object": {
                      "id": "%s",
                      "email": "consignor@example.com",
                      "business_profile": { "name": "Consignor LLC" },
                      "country": "US",
                      "payouts_enabled": true,
                      "charges_enabled": true,
                      "details_submitted": true,
                      "requirements": { "disabled_reason": null },
                      "capabilities": { "card_payments": "active" }
                    }
                  }
                }
                """, accountId);
    }
}
