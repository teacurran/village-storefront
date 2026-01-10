package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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

import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Refund;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.payment.PaymentProvider.RefundResult;
import villagecompute.storefront.payment.PaymentProvider.RefundStatus;
import villagecompute.storefront.payment.stripe.StripePaymentProvider;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTest
class PaymentServiceTest {

    @Inject
    PaymentService paymentService;

    @Inject
    EntityManager entityManager;

    @InjectMock
    StripePaymentProvider stripePaymentProvider;

    private Tenant tenant;
    private PaymentIntent paymentIntent;

    @BeforeEach
    @Transactional
    void setUp() {
        Tenant existing = Tenant.find("subdomain", "payment-service-test").firstResult();
        if (existing != null) {
            Refund.delete("tenant.id", existing.id);
            PaymentIntent.delete("tenant.id", existing.id);
            existing.delete();
        }

        tenant = new Tenant();
        tenant.subdomain = "payment-service-test";
        tenant.name = "Payment Service Test";
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        tenant.persist();

        paymentIntent = new PaymentIntent();
        paymentIntent.tenant = tenant;
        paymentIntent.provider = "stripe";
        paymentIntent.providerPaymentId = "pi_test";
        paymentIntent.amount = new BigDecimal("100.00");
        paymentIntent.currency = "USD";
        paymentIntent.status = PaymentIntent.PaymentStatus.CAPTURED;
        paymentIntent.captureMethod = PaymentIntent.CaptureMethod.AUTOMATIC;
        paymentIntent.amountCaptured = new BigDecimal("100.00");
        paymentIntent.createdAt = Instant.now();
        paymentIntent.updatedAt = Instant.now();
        paymentIntent.persist();

        TenantContext.setCurrentTenantId(tenant.id);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        if (tenant != null) {
            Refund.delete("tenant.id", tenant.id);
            PaymentIntent.delete("tenant.id", tenant.id);
            tenant.delete();
        }
        TenantContext.clear();
    }

    @Test
    void refundPaymentPersistsRefundRecord() {
        when(stripePaymentProvider.refundPayment(eq("pi_test"), any(), any())).thenReturn(
                new RefundResult("re_test", new BigDecimal("25.00"), RefundStatus.SUCCEEDED, Map.of()));

        paymentService.refundPayment(paymentIntent.id, new BigDecimal("25.00"), "requested_by_customer");

        Refund refund = Refund.find("providerRefundId", "re_test").firstResult();
        assertNotNull(refund, "Refund entity should persist for processed refunds");
        assertEquals(0, new BigDecimal("25.00").compareTo(refund.amount));
        assertEquals("requested_by_customer", refund.reason);

        PaymentIntent refreshed = PaymentIntent.findById(paymentIntent.id);
        assertEquals(0, new BigDecimal("25.00").compareTo(refreshed.amountRefunded));
    }
}
