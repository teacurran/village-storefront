package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.PaymentIntent;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ValidationStatus;
import villagecompute.storefront.services.CheckoutSaga.CheckoutRequest;
import villagecompute.storefront.services.CheckoutSaga.CheckoutResult;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * Integration tests for {@link CheckoutSaga}, verifying idempotency handling and event recording.
 */
@QuarkusTest
class CheckoutSagaTest {

    @Inject
    CheckoutSaga checkoutSaga;

    @Inject
    CartService cartService;

    @Inject
    EntityManager entityManager;

    @InjectMock
    PaymentService paymentService;

    @InjectMock
    FeatureToggle featureToggle;

    @InjectMock
    ShippingService shippingService;

    private UUID tenantId;
    private UUID userId;
    private UUID variantId;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM DomainEvent").executeUpdate();
        entityManager.createQuery("DELETE FROM OrderLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Order").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        entityManager.createQuery("DELETE FROM IdempotencyKey").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();

        Tenant tenant = new Tenant();
        tenant.subdomain = "checkout-test";
        tenant.name = "Checkout Test Tenant";
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        entityManager.persist(tenant);
        tenantId = tenant.id;

        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        User user = new User();
        user.tenant = tenant;
        user.email = "checkout@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = now;
        user.updatedAt = now;
        entityManager.persist(user);
        userId = user.id;

        Product product = new Product();
        product.tenant = tenant;
        product.name = "Checkout Product";
        product.slug = "checkout-product";
        product.type = "physical";
        product.sku = "CHK-PROD";
        product.status = "active";
        product.createdAt = now;
        product.updatedAt = now;
        entityManager.persist(product);

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.name = "Default";
        variant.sku = "CHK-VAR";
        variant.price = new BigDecimal("40.00");
        variant.status = "active";
        variant.createdAt = now;
        variant.updatedAt = now;
        entityManager.persist(variant);
        variantId = variant.id;

        when(featureToggle.isCheckoutEnabled()).thenReturn(true);
        Address normalized = new Address("123 Checkout", null, "Checkout City", "CA", "94102", "US", true);
        AddressValidationResult validationResult = new AddressValidationResult(ValidationStatus.VALID, normalized,
                List.of(), null, Map.of());
        when(shippingService.validateAddress(any(), anyString())).thenReturn(validationResult);
    }

    @AfterEach
    @Transactional
    void tearDown() {
        entityManager.createQuery("DELETE FROM DomainEvent").executeUpdate();
        entityManager.createQuery("DELETE FROM OrderLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Order").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM IdempotencyKey").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        TenantContext.clear();
    }

    @Test
    @Transactional
    void execute_shouldCompleteCheckoutAndRecordEvents() {
        CheckoutRequest request = prepareRequest("idem-123");

        CheckoutResult result = checkoutSaga.execute(request);

        assertNotNull(result);
        assertEquals("PAID", result.orderStatus());
        assertEquals("USD", result.currency());
        assertNotNull(result.paidAt());

        Order order = Order.findById(result.orderId());
        assertEquals(Order.OrderStatus.PAID, order.status);

        long initiatedEvents = DomainEvent.count("aggregateId = ?1 AND eventType = ?2", order.id, "OrderInitiated");
        long paidEvents = DomainEvent.count("aggregateId = ?1 AND eventType = ?2", order.id, "OrderPaid");
        assertTrue(initiatedEvents == 1 && paidEvents == 1, "Order events should be recorded");

        verify(paymentService, times(1)).createPaymentIntent(any(BigDecimal.class), anyString(), any(UUID.class),
                anyBoolean(), anyString());
    }

    @Test
    @Transactional
    void execute_shouldReturnCachedResultOnDuplicateKey() {
        CheckoutRequest request = prepareRequest("idem-dup");

        CheckoutResult first = checkoutSaga.execute(request);
        CheckoutResult second = checkoutSaga.execute(request);

        assertEquals(first.orderId(), second.orderId());
        assertEquals(1, Order.count());

        verify(paymentService, times(1)).createPaymentIntent(any(BigDecimal.class), anyString(), any(UUID.class),
                anyBoolean(), anyString());
    }

    private CheckoutRequest prepareRequest(String idempotencyKey) {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 1);

        PaymentIntent intent = new PaymentIntent();
        intent.id = 100L;
        intent.providerPaymentId = "pi_mock_" + idempotencyKey;
        intent.currency = "USD";
        intent.amount = new BigDecimal("40.00");
        intent.status = PaymentIntent.PaymentStatus.CAPTURED;
        intent.captureMethod = PaymentIntent.CaptureMethod.AUTOMATIC;
        intent.createdAt = OffsetDateTime.now().toInstant();
        intent.updatedAt = intent.createdAt;
        intent.version = 0L;

        when(paymentService.createPaymentIntent(any(BigDecimal.class), anyString(), any(UUID.class), anyBoolean(),
                anyString())).thenReturn(intent);

        return new CheckoutRequest(cart.id, "checkout@example.com", "{\"line1\":\"123 Checkout\"}",
                "{\"line1\":\"123 Checkout\"}", new BigDecimal("5.00"), new BigDecimal("3.00"), "USD", "pm_card_visa",
                idempotencyKey, null, null);
    }
}
