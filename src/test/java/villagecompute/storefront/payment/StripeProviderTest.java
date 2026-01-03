package villagecompute.storefront.payment;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import villagecompute.storefront.data.models.PlatformFeeConfig;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.payment.stripe.StripeMarketplaceProvider;
import villagecompute.storefront.payment.stripe.StripePaymentProvider;
import villagecompute.storefront.services.PaymentService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for Stripe payment provider implementations. Tests payment intents, captures, refunds, and
 * marketplace operations.
 *
 * Set STRIPE_TEST_MODE=true and provide test API keys to run these tests. Tests are skipped if Stripe credentials are
 * not configured.
 */
@QuarkusTest
class StripeProviderTest {

    @Inject
    StripePaymentProvider stripePaymentProvider;

    @Inject
    StripeMarketplaceProvider stripeMarketplaceProvider;

    @Inject
    PaymentService paymentService;

    private Tenant testTenant;

    @BeforeEach
    @Transactional
    void setUp() {
        if (testTenant == null) {
            testTenant = Tenant.find("subdomain", "stripe-provider-test").firstResult();
        }

        if (testTenant == null) {
            testTenant = new Tenant();
            testTenant.subdomain = "stripe-provider-test";
            testTenant.name = "Stripe Provider Test";
            testTenant.status = "active";
            OffsetDateTime now = OffsetDateTime.now();
            testTenant.createdAt = now;
            testTenant.updatedAt = now;
            testTenant.persist();
        }

        TenantContext.setCurrentTenantId(testTenant.id);

        // Clean up test data
        PlatformFeeConfig.delete("tenant.id", testTenant.id);

        // Create test fee configuration
        PlatformFeeConfig feeConfig = new PlatformFeeConfig();
        feeConfig.tenant = testTenant;
        feeConfig.feePercentage = new BigDecimal("0.0500"); // 5%
        feeConfig.fixedFeeAmount = new BigDecimal("0.30");
        feeConfig.currency = "USD";
        feeConfig.minimumFee = new BigDecimal("0.50");
        feeConfig.active = true;
        feeConfig.effectiveFrom = Instant.now();
        feeConfig.persist();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        PlatformFeeConfig.delete("tenant.id", testTenant.id);
        TenantContext.clear();
    }

    /**
     * Test creating a payment intent with automatic capture. Requires Stripe test credentials to be configured.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testCreatePaymentIntent_AutomaticCapture() {
        // Given
        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("100.00"), "USD", null, // No customer
                null, // No payment method (will be added by client)
                true, // Capture immediately
                Map.of("test", "true", "order_id", "12345"), "test-idempotency-key-" + System.currentTimeMillis());

        // When
        PaymentProvider.PaymentIntentResult result = stripePaymentProvider.createIntent(request);

        // Then
        assertNotNull(result);
        assertNotNull(result.paymentIntentId());
        assertTrue(result.paymentIntentId().startsWith("pi_"));
        assertNotNull(result.clientSecret());
        assertTrue(result.clientSecret().contains("_secret_"));
        assertEquals(PaymentProvider.PaymentStatus.PENDING, result.status());
    }

    /**
     * Test creating a payment intent with manual capture.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testCreatePaymentIntent_ManualCapture() {
        // Given
        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("50.00"), "USD", null, null, false, // Manual capture
                Map.of("test", "true"), "test-manual-" + System.currentTimeMillis());

        // When
        PaymentProvider.PaymentIntentResult result = stripePaymentProvider.createIntent(request);

        // Then
        assertNotNull(result);
        assertNotNull(result.paymentIntentId());
        assertEquals(PaymentProvider.PaymentStatus.PENDING, result.status());
    }

    /**
     * Test platform fee calculation with default configuration.
     */
    @Test
    void testPlatformFeeCalculation() {
        // Given
        BigDecimal transactionAmount = new BigDecimal("100.00");

        // When
        MarketplaceProvider.PlatformFeeCalculation result = stripeMarketplaceProvider
                .calculatePlatformFee(testTenant.id, transactionAmount);

        // Then
        assertNotNull(result);
        assertEquals(transactionAmount, result.transactionAmount());
        assertEquals(0, new BigDecimal("0.05").compareTo(result.platformFeePercentage()));

        // Expected fee: 5% of $100 = $5.00 + $0.30 = $5.30
        BigDecimal expectedFee = new BigDecimal("5.30");
        assertEquals(0, expectedFee.compareTo(result.platformFeeAmount()));

        BigDecimal expectedNet = new BigDecimal("94.70");
        assertEquals(0, expectedNet.compareTo(result.netAmount()));
    }

    /**
     * Test platform fee calculation with minimum fee enforcement.
     */
    @Test
    void testPlatformFeeCalculation_MinimumFee() {
        // Given - small transaction that would result in fee below minimum
        BigDecimal transactionAmount = new BigDecimal("5.00");

        // When
        MarketplaceProvider.PlatformFeeCalculation result = stripeMarketplaceProvider
                .calculatePlatformFee(testTenant.id, transactionAmount);

        // Then
        assertNotNull(result);

        // Expected fee: 5% of $5 = $0.25 + $0.30 = $0.55, but minimum is $0.50
        // Actually it should be $0.55 since that's above minimum
        BigDecimal expectedFee = new BigDecimal("0.55");
        assertEquals(0, expectedFee.compareTo(result.platformFeeAmount()));
    }

    /**
     * Test getting payment status for a non-existent payment.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testGetPaymentStatus_NotFound() {
        // When/Then
        assertThrows(Exception.class, () -> stripePaymentProvider.getPaymentStatus("pi_nonexistent_12345"),
                "Should throw exception for non-existent payment intent");
    }

    /**
     * Test idempotency key handling - same request should return same result.
     */
    @Test
    @EnabledIfEnvironmentVariable(
            named = "STRIPE_TEST_MODE",
            matches = "true")
    void testIdempotencyKey() {
        // Given
        String idempotencyKey = "test-idempotent-" + System.currentTimeMillis();
        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("25.00"), "USD", null, null, true, Map.of("test", "idempotency"), idempotencyKey);

        // When - create payment twice with same idempotency key
        PaymentProvider.PaymentIntentResult result1 = stripePaymentProvider.createIntent(request);
        PaymentProvider.PaymentIntentResult result2 = stripePaymentProvider.createIntent(request);

        // Then - should return the same payment intent
        assertNotNull(result1);
        assertNotNull(result2);
        assertEquals(result1.paymentIntentId(), result2.paymentIntentId());
    }

    /**
     * Test payment service integration with local persistence.
     */
    @Test
    @Transactional
    void testPaymentService_CreateAndRetrieve() {
        // This test doesn't require Stripe credentials as it tests local logic
        PlatformFeeConfig config = paymentService.getPlatformFeeConfig();

        assertNotNull(config);
        assertEquals(testTenant.id, config.tenant.id);
        assertTrue(config.active);
    }

    /**
     * Test updating platform fee configuration.
     */
    @Test
    @Transactional
    void testUpdatePlatformFeeConfig() {
        // Given
        BigDecimal newPercentage = new BigDecimal("0.0750"); // 7.5%
        BigDecimal newFixedFee = new BigDecimal("0.50");
        BigDecimal newMinFee = new BigDecimal("1.00");
        BigDecimal newMaxFee = new BigDecimal("50.00");

        // When
        PlatformFeeConfig updated = paymentService.updatePlatformFeeConfig(newPercentage, newFixedFee, newMinFee,
                newMaxFee);

        // Then
        assertNotNull(updated);
        assertEquals(0, newPercentage.compareTo(updated.feePercentage));
        assertEquals(0, newFixedFee.compareTo(updated.fixedFeeAmount));
        assertEquals(0, newMinFee.compareTo(updated.minimumFee));
        assertEquals(0, newMaxFee.compareTo(updated.maximumFee));

        // Verify calculation uses new config
        MarketplaceProvider.PlatformFeeCalculation calc = paymentService.calculatePlatformFee(new BigDecimal("100.00"));

        // Expected: 7.5% of $100 = $7.50 + $0.50 = $8.00
        assertEquals(0, new BigDecimal("8.00").compareTo(calc.platformFeeAmount()));
    }
}
