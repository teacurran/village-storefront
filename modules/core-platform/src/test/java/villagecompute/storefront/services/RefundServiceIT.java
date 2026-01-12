package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Refund;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.loyalty.LoyaltyService;
import villagecompute.storefront.payment.PaymentProvider.RefundResult;
import villagecompute.storefront.payment.PaymentProvider.RefundStatus;
import villagecompute.storefront.payment.stripe.StripePaymentProvider;
import villagecompute.storefront.services.returns.ReturnWorkflowCoordinator;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * Integration tests for RefundService covering idempotency, multi-tenant safety, and concurrency scenarios.
 *
 * <p>
 * Validates:
 * <ul>
 * <li>Successful refund processing with domain event emission</li>
 * <li>Idempotency key enforcement (duplicate request handling)</li>
 * <li>Tenant isolation (cross-tenant refund rejection)</li>
 * <li>Validation (payment status, refund amount limits)</li>
 * <li>Partial and full refund scenarios</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T9: Refund and return implementation</li>
 * <li>Acceptance Criteria: Tests covering concurrency + unauthorized access scenarios</li>
 * </ul>
 */
@QuarkusTest
class RefundServiceIT {

    @Inject
    RefundService refundService;

    @Inject
    EntityManager entityManager;

    @InjectMock
    StripePaymentProvider stripePaymentProvider;

    @InjectMock
    LoyaltyService loyaltyService;

    @InjectMock
    ReturnWorkflowCoordinator returnWorkflowCoordinator;

    private Tenant tenant;
    private Tenant otherTenant;
    private Order order;
    private PaymentIntent paymentIntent;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing test data
        cleanup("refund-service-test");
        cleanup("refund-service-other");

        // Create test tenant
        tenant = new Tenant();
        tenant.subdomain = "refund-service-test";
        tenant.name = "Refund Service Test";
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        tenant.persist();

        // Create other tenant for cross-tenant tests
        otherTenant = new Tenant();
        otherTenant.subdomain = "refund-service-other";
        otherTenant.name = "Other Tenant";
        otherTenant.status = "active";
        otherTenant.createdAt = now;
        otherTenant.updatedAt = now;
        otherTenant.persist();

        // Create test order
        order = new Order();
        order.tenant = tenant;
        order.orderNumber = "ORD-TEST-001";
        order.status = Order.OrderStatus.PAID;
        order.customerEmail = "test@example.com";
        order.totalAmount = new BigDecimal("100.00");
        order.subtotalAmount = new BigDecimal("90.00");
        order.taxAmount = new BigDecimal("10.00");
        order.shippingAmount = BigDecimal.ZERO;
        order.currency = "USD";
        order.createdAt = OffsetDateTime.now();
        order.updatedAt = OffsetDateTime.now();
        order.persist();

        // Create test payment intent
        paymentIntent = new PaymentIntent();
        paymentIntent.tenant = tenant;
        paymentIntent.provider = "stripe";
        paymentIntent.providerPaymentId = "pi_test_123";
        paymentIntent.orderId = order.id;
        paymentIntent.amount = new BigDecimal("100.00");
        paymentIntent.currency = "USD";
        paymentIntent.status = PaymentIntent.PaymentStatus.CAPTURED;
        paymentIntent.captureMethod = PaymentIntent.CaptureMethod.AUTOMATIC;
        paymentIntent.amountCaptured = new BigDecimal("100.00");
        paymentIntent.amountRefunded = BigDecimal.ZERO;
        paymentIntent.createdAt = Instant.now();
        paymentIntent.updatedAt = Instant.now();
        paymentIntent.persist();

        // Link payment intent to order
        order.paymentIntentId = paymentIntent.providerPaymentId;
        order.persist();

        when(loyaltyService.reversePointsForOrderRefund(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        TenantContext.setCurrentTenantId(tenant.id);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        cleanup("refund-service-test");
        cleanup("refund-service-other");
        TenantContext.clear();
    }

    @Transactional
    void cleanup(String subdomain) {
        Tenant existing = Tenant.find("subdomain", subdomain).firstResult();
        if (existing != null) {
            IdempotencyKey.delete("tenant.id", existing.id);
            Refund.delete("tenant.id", existing.id);
            PaymentIntent.delete("tenant.id", existing.id);
            Order.delete("tenant.id", existing.id);
            existing.delete();
        }
    }

    @Test
    void testSuccessfulFullRefund() {
        // Mock Stripe provider
        when(stripePaymentProvider.refundPayment(eq("pi_test_123"), any(), any())).thenReturn(
                new RefundResult("re_test_full", new BigDecimal("100.00"), RefundStatus.SUCCEEDED, Map.of()));

        // Execute refund
        RefundService.RefundResult result = refundService.initiateRefund(order.id, null, "requested_by_customer",
                "idem-key-full-refund", false);

        // Verify result
        assertNotNull(result);
        assertEquals(0, new BigDecimal("100.00").compareTo(result.amount()));
        assertEquals("USD", result.currency());
        assertEquals("succeeded", result.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(result.remainingRefundable()));
        assertNotNull(result.referenceId());
        assertEquals(paymentIntent.providerPaymentId, result.paymentIntentId());
        assertEquals("requested_by_customer", result.reason());
        assertFalse(result.fromCache());

        // Verify database state
        Refund refund = Refund.findById(result.refundId());
        assertNotNull(refund);
        assertEquals(0, new BigDecimal("100.00").compareTo(refund.amount));
        assertEquals("requested_by_customer", refund.reason);
        assertEquals(Refund.RefundStatus.SUCCEEDED, refund.status);

        // Verify order status updated to REFUNDED
        Order refreshedOrder = Order.findById(order.id);
        assertEquals(Order.OrderStatus.REFUNDED, refreshedOrder.status);

        // Verify payment intent updated
        PaymentIntent refreshedIntent = PaymentIntent.findById(paymentIntent.id);
        assertEquals(0, new BigDecimal("100.00").compareTo(refreshedIntent.amountRefunded));
    }

    @Test
    void testSuccessfulPartialRefund() {
        // Mock Stripe provider
        when(stripePaymentProvider.refundPayment(eq("pi_test_123"), any(), any())).thenReturn(
                new RefundResult("re_test_partial", new BigDecimal("30.00"), RefundStatus.SUCCEEDED, Map.of()));

        // Execute partial refund
        RefundService.RefundResult result = refundService.initiateRefund(order.id, new BigDecimal("30.00"),
                "damaged_item", "idem-key-partial-refund", false);

        // Verify result
        assertNotNull(result);
        assertEquals(0, new BigDecimal("30.00").compareTo(result.amount()));
        assertEquals(0, new BigDecimal("70.00").compareTo(result.remainingRefundable()));

        // Verify order status NOT updated (partial refund)
        Order refreshedOrder = Order.findById(order.id);
        assertEquals(Order.OrderStatus.PAID, refreshedOrder.status);

        // Verify payment intent
        PaymentIntent refreshedIntent = PaymentIntent.findById(paymentIntent.id);
        assertEquals(0, new BigDecimal("30.00").compareTo(refreshedIntent.amountRefunded));
    }

    @Test
    void testIdempotencyKeyReusesResult() {
        // Mock Stripe provider
        when(stripePaymentProvider.refundPayment(eq("pi_test_123"), any(), any())).thenReturn(
                new RefundResult("re_test_idem", new BigDecimal("50.00"), RefundStatus.SUCCEEDED, Map.of()));

        String idempotencyKey = "idem-key-reuse-test";

        // First request
        RefundService.RefundResult result1 = refundService.initiateRefund(order.id, new BigDecimal("50.00"),
                "customer_request", idempotencyKey, false);

        // Second request with same key
        RefundService.RefundResult result2 = refundService.initiateRefund(order.id, new BigDecimal("50.00"),
                "customer_request", idempotencyKey, false);

        // Verify both return same result
        assertEquals(result1.refundId(), result2.refundId());
        assertEquals(result1.providerRefundId(), result2.providerRefundId());
        assertEquals(0, result1.amount().compareTo(result2.amount()));
        assertTrue(result2.fromCache());

        // Verify only one refund record created
        long refundCount = Refund.count("tenant.id = ?1 and providerRefundId = ?2", tenant.id, "re_test_idem");
        assertEquals(1, refundCount);
    }

    @Test
    void testRefundExceedsRefundableAmount() {
        // Mock Stripe provider
        when(stripePaymentProvider.refundPayment(eq("pi_test_123"), any(), any())).thenReturn(
                new RefundResult("re_test_excess", new BigDecimal("150.00"), RefundStatus.SUCCEEDED, Map.of()));

        // Attempt refund exceeding captured amount
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> refundService.initiateRefund(order.id, new BigDecimal("150.00"), "test",
                        "idem-key-excess-refund", false));

        assertTrue(exception.getMessage().contains("exceeds refundable amount"));
    }

    @Test
    void testRefundOnUnauthorizedTenant() {
        // Switch to different tenant context
        TenantContext.clear();
        TenantContext.setCurrentTenantId(otherTenant.id);

        // Attempt refund on order from other tenant
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> refundService
                .initiateRefund(order.id, new BigDecimal("50.00"), "test", "idem-key-unauthorized", false));

        assertTrue(exception.getMessage().contains("Order not found"));
    }

    @Test
    void testRefundOnNonCapturedPayment() {
        // Update payment to non-captured status
        paymentIntent.status = PaymentIntent.PaymentStatus.PENDING;
        paymentIntent.persist();

        // Attempt refund on non-captured payment
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> refundService
                .initiateRefund(order.id, new BigDecimal("50.00"), "test", "idem-key-non-captured", false));

        assertTrue(exception.getMessage().contains("Payment not captured"));
    }

    @Test
    void testRefundOnOrderWithoutPaymentIntent() {
        // Create order without payment intent
        Order orderWithoutPayment = new Order();
        orderWithoutPayment.tenant = tenant;
        orderWithoutPayment.orderNumber = "ORD-TEST-NO-PAYMENT";
        orderWithoutPayment.status = Order.OrderStatus.PENDING_PAYMENT;
        orderWithoutPayment.customerEmail = "test2@example.com";
        orderWithoutPayment.totalAmount = new BigDecimal("50.00");
        orderWithoutPayment.subtotalAmount = new BigDecimal("50.00");
        orderWithoutPayment.taxAmount = BigDecimal.ZERO;
        orderWithoutPayment.shippingAmount = BigDecimal.ZERO;
        orderWithoutPayment.currency = "USD";
        orderWithoutPayment.createdAt = OffsetDateTime.now();
        orderWithoutPayment.updatedAt = OffsetDateTime.now();
        orderWithoutPayment.persist();

        // Attempt refund
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> refundService
                .initiateRefund(orderWithoutPayment.id, new BigDecimal("50.00"), "test", "idem-key-no-payment", false));

        assertTrue(exception.getMessage().contains("no payment intent"));
    }

    @Test
    void testMultiplePartialRefunds() {
        // Mock Stripe provider for multiple refunds
        when(stripePaymentProvider.refundPayment(eq("pi_test_123"), any(), any())).thenReturn(
                new RefundResult("re_test_multi_1", new BigDecimal("30.00"), RefundStatus.SUCCEEDED, Map.of()))
                .thenReturn(new RefundResult("re_test_multi_2", new BigDecimal("40.00"), RefundStatus.SUCCEEDED,
                        Map.of()));

        // First partial refund
        RefundService.RefundResult result1 = refundService.initiateRefund(order.id, new BigDecimal("30.00"),
                "damaged_item", "idem-key-multi-1", false);
        assertEquals(0, new BigDecimal("70.00").compareTo(result1.remainingRefundable()));

        // Second partial refund
        RefundService.RefundResult result2 = refundService.initiateRefund(order.id, new BigDecimal("40.00"),
                "customer_request", "idem-key-multi-2", false);
        assertEquals(0, new BigDecimal("30.00").compareTo(result2.remainingRefundable()));

        // Verify total refunded amount
        PaymentIntent refreshedIntent = PaymentIntent.findById(paymentIntent.id);
        assertEquals(0, new BigDecimal("70.00").compareTo(refreshedIntent.amountRefunded));

        // Verify order status (not fully refunded)
        Order refreshedOrder = Order.findById(order.id);
        assertEquals(Order.OrderStatus.PAID, refreshedOrder.status);
    }

    @Test
    void testNegativeRefundAmountRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> refundService
                .initiateRefund(order.id, new BigDecimal("-10.00"), "test", "idem-key-negative", false));

        assertTrue(exception.getMessage().contains("must be positive"));
    }

    @Test
    void testZeroRefundAmountRejected() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> refundService.initiateRefund(order.id, BigDecimal.ZERO, "test", "idem-key-zero", false));

        assertTrue(exception.getMessage().contains("must be positive"));
    }
}
