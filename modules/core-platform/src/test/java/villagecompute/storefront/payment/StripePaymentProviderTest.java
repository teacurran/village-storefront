package villagecompute.storefront.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.payment.stripe.StripeConfig;
import villagecompute.storefront.payment.stripe.StripePaymentProvider;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * Unit tests for StripePaymentProvider with mocked Stripe SDK. Tests payment intent lifecycle, error handling, and
 * idempotency without making real API calls.
 */
@QuarkusTest
class StripePaymentProviderTest {

    @Inject
    StripePaymentProvider stripePaymentProvider;

    @InjectMock
    StripeConfig stripeConfig;

    private Tenant testTenant;

    @BeforeEach
    @Transactional
    void setUp() {
        if (testTenant == null) {
            testTenant = Tenant.find("subdomain", "provider-test").firstResult();
        }

        if (testTenant == null) {
            testTenant = new Tenant();
            testTenant.subdomain = "provider-test";
            testTenant.name = "Provider Test";
            testTenant.status = "active";
            OffsetDateTime now = OffsetDateTime.now();
            testTenant.createdAt = now;
            testTenant.updatedAt = now;
            testTenant.persist();
        }

        TenantContext.setCurrentTenantId(testTenant.id);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void testCreateIntentAppliesApplicationFeeAndTransferData() {
        stubStripeConfig(true);

        PaymentProvider.CreatePaymentIntentRequest request = new PaymentProvider.CreatePaymentIntentRequest(
                new BigDecimal("120.00"), "USD", "cus_123", "pm_987", true, Map.of("order_id", "ORD-123"),
                "test-idem-123", new BigDecimal("6.00"), "acct_123");

        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        when(paymentIntent.getId()).thenReturn("pi_test_123");
        when(paymentIntent.getClientSecret()).thenReturn("cs_test_123");
        when(paymentIntent.getStatus()).thenReturn("succeeded");

        try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                    .thenAnswer(invocation -> {
                        PaymentIntentCreateParams params = invocation.getArgument(0);
                        RequestOptions options = invocation.getArgument(1);
                        assertEquals(12000L, params.getAmount());
                        assertEquals(600L, params.getApplicationFeeAmount());
                        assertEquals("acct_123", params.getTransferData().getDestination());
                        assertEquals("test-idem-123", options.getIdempotencyKey());
                        assertEquals("ORD-123", params.getMetadata().get("order_id"));
                        assertEquals(TenantContext.getCurrentTenantId().toString(),
                                params.getMetadata().get("tenant_id"));
                        return paymentIntent;
                    });

            PaymentProvider.PaymentIntentResult result = stripePaymentProvider.createIntent(request);
            assertEquals("pi_test_123", result.paymentIntentId());
            assertEquals(PaymentProvider.PaymentStatus.CAPTURED, result.status());
            assertNotNull(result.clientSecret());
        }
    }

    @Test
    void testCapturePaymentSendsRequestedAmount() throws Exception {
        stubStripeConfig(false);

        PaymentIntent paymentIntent = mock(PaymentIntent.class);
        PaymentIntent capturedIntent = mock(PaymentIntent.class);
        when(capturedIntent.getId()).thenReturn("pi_cap");
        when(capturedIntent.getAmountReceived()).thenReturn(4250L);
        when(capturedIntent.getStatus()).thenReturn("succeeded");

        when(paymentIntent.capture(any(PaymentIntentCaptureParams.class))).thenAnswer(invocation -> {
            PaymentIntentCaptureParams params = invocation.getArgument(0);
            assertEquals(4250L, params.getAmountToCapture());
            return capturedIntent;
        });

        try (MockedStatic<PaymentIntent> mocked = Mockito.mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve("pi_cap")).thenReturn(paymentIntent);

            PaymentProvider.CaptureResult result = stripePaymentProvider.capturePayment("pi_cap",
                    new BigDecimal("42.50"));
            assertEquals(new BigDecimal("42.50"), result.amountCaptured());
            assertEquals(PaymentProvider.PaymentStatus.CAPTURED, result.status());
        }
    }

    @Test
    void testRefundPaymentWrapsStripeException() {
        stubStripeConfig(false);

        try (MockedStatic<Refund> mockedRefund = Mockito.mockStatic(Refund.class)) {
            mockedRefund.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenThrow(new ApiException("boom", "req_123", "api_error", 500, null));

            assertThrows(StripePaymentProvider.PaymentProviderException.class, () -> stripePaymentProvider
                    .refundPayment("pi_fail", new BigDecimal("10.00"), "requested_by_customer"));
        }
    }

    private void stubStripeConfig(boolean connectEnabled) {
        when(stripeConfig.apiSecretKey()).thenReturn("sk_test_unit");
        when(stripeConfig.maxRetries()).thenReturn(2);
        when(stripeConfig.connectEnabled()).thenReturn(connectEnabled);
    }
}
