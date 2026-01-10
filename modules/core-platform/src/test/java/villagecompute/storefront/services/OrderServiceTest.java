package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.OrderLineItem;
import villagecompute.storefront.data.models.Product;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link OrderService}. Validates multi-tenant safety, domain events, and totals calculation.
 */
@QuarkusTest
class OrderServiceTest {

    @Inject
    OrderService orderService;

    @Inject
    CartService cartService;

    @Inject
    ConsignmentService consignmentService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID userId;
    private UUID variantId;
    private UUID productId;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM DomainEvent").executeUpdate();
        entityManager.createQuery("DELETE FROM OrderLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Order").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLedgerEntry").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLedger").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        Tenant tenant = new Tenant();
        tenant.subdomain = "order-test";
        tenant.name = "Order Test Tenant";
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        entityManager.persist(tenant);
        tenantId = tenant.id;

        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        User user = new User();
        user.tenant = tenant;
        user.email = "buyer@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = now;
        user.updatedAt = now;
        entityManager.persist(user);
        userId = user.id;

        Product product = new Product();
        product.tenant = tenant;
        product.sku = "ORDER-PRODUCT";
        product.name = "Order Product";
        product.slug = "order-product";
        product.type = "physical";
        product.status = "active";
        product.createdAt = now;
        product.updatedAt = now;
        entityManager.persist(product);
        productId = product.id;

        ProductVariant variant = new ProductVariant();
        variant.tenant = tenant;
        variant.product = product;
        variant.name = "Default Variant";
        variant.sku = "ORDER-VARIANT";
        variant.price = new BigDecimal("29.00");
        variant.status = "active";
        variant.createdAt = now;
        variant.updatedAt = now;
        entityManager.persist(variant);
        variantId = variant.id;
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
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLedgerEntry").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLedger").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
        TenantContext.clear();
    }

    @Test
    @Transactional
    void createOrderFromCart_shouldPersistOrderAndEvents() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 2);

        Order order = orderService.createOrderFromCart(cart, "buyer@example.com", "{\"line1\":\"123\"}",
                "{\"line1\":\"456\"}", new BigDecimal("10.00"), new BigDecimal("2.00"), "USD");

        assertNotNull(order.id);
        assertEquals(Order.OrderStatus.PENDING_PAYMENT, order.status);
        assertEquals(0, order.subtotalAmount.compareTo(new BigDecimal("58.00")));
        assertEquals(0, order.shippingAmount.compareTo(new BigDecimal("10.00")));
        assertEquals(0, order.taxAmount.compareTo(new BigDecimal("2.00")));
        assertEquals(0, order.totalAmount.compareTo(new BigDecimal("70.00")));

        List<DomainEvent> events = DomainEvent.find("aggregateId = ?1 AND eventType = ?2", order.id, "OrderInitiated")
                .list();
        assertFalse(events.isEmpty(), "OrderInitiated event should be recorded");
    }

    @Test
    @Transactional
    void createOrderFromCart_shouldPopulateConsignmentMetadata() {
        Consignor consignor = new Consignor();
        consignor.name = "Consignment Vendor";
        consignor.contactInfo = "{\"email\":\"vendor@example.com\"}";
        consignor.payoutSettings = "{\"default_commission_rate\":12.0}";
        consignor.status = "active";
        Consignor persistedConsignor = consignmentService.createConsignor(consignor);

        consignmentService.createConsignmentItem(persistedConsignor.id, productId, new BigDecimal("12.00"));

        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 1);

        Order order = orderService.createOrderFromCart(cart, "buyer@example.com", "{\"line1\":\"123\"}",
                "{\"line1\":\"456\"}", BigDecimal.ZERO, BigDecimal.ZERO, "USD");

        OrderLineItem lineItem = OrderLineItem.find("order.id = ?1", order.id).firstResult();
        assertNotNull(lineItem);
        assertEquals(persistedConsignor.id, lineItem.vendorId);
        assertEquals(new BigDecimal("0.1200"), lineItem.commissionRate.setScale(4));
    }

    @Test
    @Transactional
    void markOrderPaid_shouldEmitOrderPaidEvent() {
        Cart cart = cartService.getOrCreateCartForUser(userId);
        cartService.addItemToCart(cart.id, variantId, 1);
        Order order = orderService.createOrderFromCart(cart, "buyer@example.com", "{\"line1\":\"123\"}",
                "{\"line1\":\"456\"}", BigDecimal.ZERO, BigDecimal.ZERO, "USD");

        orderService.markOrderPaid(order.id, "pi_test");

        Order refreshed = Order.findById(order.id);
        assertEquals(Order.OrderStatus.PAID, refreshed.status);
        assertEquals("pi_test", refreshed.paymentIntentId);
        assertNotNull(refreshed.paidAt);

        List<DomainEvent> events = DomainEvent.find("aggregateId = ?1 AND eventType = ?2", order.id, "OrderPaid")
                .list();
        assertTrue(events.size() == 1, "OrderPaid event should exist");
    }
}
