package villagecompute.storefront.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.FeatureToggle;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.mailer.Mail;
import io.quarkus.mailer.MockMailbox;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for order email notifications.
 *
 * <p>
 * Tests cover order confirmation, shipped, delivered, and cancelled email template rendering, i18n placeholders,
 * feature flag gating, and Mailer integration.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T5: Order email templates and localization</li>
 * <li>Acceptance Criteria: Templates render with order data, i18n placeholders resolved, domain filtering tested</li>
 * </ul>
 */
@QuarkusTest
class OrderNotificationServiceTest {

    private static final List<String> ORDER_NOTIFICATION_FLAG_KEYS = List.of("notifications.order.confirmation",
            "notifications.order.shipped", "notifications.order.delivered", "notifications.order.cancelled");

    @Inject
    NotificationService notificationService;

    @Inject
    FeatureToggle featureToggle;

    @Inject
    MockMailbox mailbox;

    @Inject
    EntityManager entityManager;

    @Inject
    NotificationJobProcessor notificationJobProcessor;

    private UUID tenantId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing order notification feature flags
        entityManager.createQuery("DELETE FROM FeatureFlag f WHERE f.flagKey IN :keys")
                .setParameter("keys", ORDER_NOTIFICATION_FLAG_KEYS).executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "ordertest-" + UUID.randomUUID().toString().substring(0, 8);
        tenant.name = "Order Notification Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();

        tenantId = tenant.id;
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        // Enable all order notification feature flags globally
        persistGlobalFlag("notifications.order.confirmation", true);
        persistGlobalFlag("notifications.order.shipped", true);
        persistGlobalFlag("notifications.order.delivered", true);
        persistGlobalFlag("notifications.order.cancelled", true);

        // Clear mailbox
        mailbox.clear();
    }

    @Transactional
    void persistGlobalFlag(String flagKey, boolean enabled) {
        FeatureFlag flag = new FeatureFlag();
        flag.tenant = null;
        flag.flagKey = flagKey;
        flag.enabled = enabled;
        flag.config = "{}";
        flag.createdAt = OffsetDateTime.now();
        flag.updatedAt = OffsetDateTime.now();
        flag.owner = "order-notifications-tests@villagecompute.dev";
        flag.lastReviewedAt = OffsetDateTime.now();
        entityManager.persist(flag);
        entityManager.flush();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        entityManager.createQuery("DELETE FROM FeatureFlag f WHERE f.flagKey IN :keys")
                .setParameter("keys", ORDER_NOTIFICATION_FLAG_KEYS).executeUpdate();

        if (tenantId != null) {
            entityManager.createQuery("DELETE FROM Tenant t WHERE t.id = :tenantId").setParameter("tenantId", tenantId)
                    .executeUpdate();
            tenantId = null;
        }

        TenantContext.clear();
    }

    @Test
    void testSendOrderConfirmation_RendersTemplateWithOrderData() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        List<Map<String, String>> lineItems = List.of(Map.of("productName", "Vintage Leather Jacket", "quantity", "1",
                "unitPrice", "$199.99", "lineTotal", "$199.99"));

        Map<String, String> shippingAddress = Map.of("name", "John Smith", "line1", "123 Main Street", "city",
                "San Francisco", "state", "CA", "postalCode", "94102", "country", "USA");

        NotificationContext context = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
                .customerName("John Smith").customerEmail("john@example.com").locale("en")
                .templateData(Map.of("orderNumber", "ORD-12345", "orderDate", "2026-01-10", "lineItems", lineItems,
                        "subtotal", "$199.99", "shipping", "$9.99", "tax", "$18.00", "total", "$227.98",
                        "shippingAddress", shippingAddress))
                .build();

        // Act
        notificationService.sendOrderConfirmation(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("john@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Order Confirmation - Thank You for Your Order", email.getSubject());
        assertTrue(email.getHtml().contains("John Smith"));
        assertTrue(email.getHtml().contains("ORD-12345"));
        assertTrue(email.getHtml().contains("Vintage Leather Jacket"));
        assertTrue(email.getHtml().contains("$227.98"));
    }

    @Test
    void testSendOrderShipped_RendersTemplateWithTrackingInfo() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        Map<String, String> shippingAddress = Map.of("name", "Jane Doe", "line1", "456 Oak Avenue", "city",
                "Los Angeles", "state", "CA", "postalCode", "90001", "country", "USA");

        NotificationContext context = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
                .customerName("Jane Doe").customerEmail("jane@example.com").locale("en")
                .templateData(Map.of("orderNumber", "ORD-67890", "shippedDate", "2026-01-11", "trackingNumber",
                        "1Z999AA10123456784", "carrier", "UPS", "estimatedDelivery", "2026-01-15", "shippingAddress",
                        shippingAddress, "trackingUrl", "https://www.ups.com/track?tracknum=1Z999AA10123456784"))
                .build();

        // Act
        notificationService.sendOrderShipped(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("jane@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Your Order Has Shipped", email.getSubject());
        assertTrue(email.getHtml().contains("Jane Doe"));
        assertTrue(email.getHtml().contains("ORD-67890"));
        assertTrue(email.getHtml().contains("1Z999AA10123456784"));
        assertTrue(email.getHtml().contains("UPS"));
    }

    @Test
    void testSendOrderDelivered_RendersTemplateWithDeliveryDetails() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        Map<String, String> shippingAddress = Map.of("name", "Carlos Gómez", "line1", "789 Pine Street", "city",
                "Miami", "state", "FL", "postalCode", "33101", "country", "USA");

        NotificationContext context = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
                .customerName("Carlos Gómez").customerEmail("carlos@example.com").locale("es")
                .templateData(Map.of("orderNumber", "ORD-11111", "deliveredDate", "2026-01-15", "deliveryLocation",
                        "Front Porch", "shippingAddress", shippingAddress))
                .build();

        // Act
        notificationService.sendOrderDelivered(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("carlos@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        // Spanish subject
        assertEquals("Su Pedido Ha Sido Entregado", email.getSubject());
        assertTrue(email.getHtml().contains("Carlos Gómez"));
        assertTrue(email.getHtml().contains("ORD-11111"));
        assertTrue(email.getHtml().contains("Front Porch"));
        // Verify Spanish i18n
        assertTrue(email.getHtml().contains("Pedido Entregado"));
    }

    @Test
    void testSendOrderCancelled_RendersTemplateWithRefundInfo() {
        // Arrange
        UUID customerId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
                .customerName("Alice Brown").customerEmail("alice@example.com").locale("en")
                .templateData(Map.of("orderNumber", "ORD-22222", "cancelledDate", "2026-01-12", "cancellationReason",
                        "Customer requested cancellation", "refundAmount", "$149.99"))
                .build();

        // Act
        notificationService.sendOrderCancelled(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("alice@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Order Cancelled - Refund Initiated", email.getSubject());
        assertTrue(email.getHtml().contains("Alice Brown"));
        assertTrue(email.getHtml().contains("ORD-22222"));
        assertTrue(email.getHtml().contains("Customer requested cancellation"));
        assertTrue(email.getHtml().contains("$149.99"));
    }

    @Test
    @Transactional
    void testOrderNotificationSkipped_WhenFeatureFlagDisabled() {
        // Arrange - Disable order confirmation flag for this tenant
        FeatureFlag disabledFlag = new FeatureFlag();
        disabledFlag.tenant = entityManager.getReference(Tenant.class, tenantId);
        disabledFlag.flagKey = "notifications.order.confirmation";
        disabledFlag.enabled = false;
        disabledFlag.config = "{}";
        disabledFlag.createdAt = OffsetDateTime.now();
        disabledFlag.updatedAt = OffsetDateTime.now();
        disabledFlag.owner = "order-notifications-tests@villagecompute.dev";
        disabledFlag.lastReviewedAt = OffsetDateTime.now();
        entityManager.persist(disabledFlag);
        entityManager.flush();

        featureToggle.invalidateAll();

        UUID customerId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
                .customerName("Test User").customerEmail("test@example.com").locale("en")
                .templateData(Map.of("orderNumber", "ORD-99999", "orderDate", "2026-01-10", "lineItems", List.of(),
                        "subtotal", "$0.00", "shipping", "$0.00", "tax", "$0.00", "total", "$0.00", "shippingAddress",
                        Map.of()))
                .build();

        // Act
        notificationService.sendOrderConfirmation(context);

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("test@example.com");
        assertEquals(0, sent.size(), "Email should not be sent when feature flag is disabled");
    }

    @Test
    void testLocaleFallback_EnglishDefault() {
        // Arrange - Use unsupported locale
        UUID customerId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).customerId(customerId)
                .customerName("Test User").customerEmail("test@example.com").locale("fr")
                .templateData(Map.of("orderNumber", "ORD-99999", "orderDate", "2026-01-10", "lineItems", List.of(),
                        "subtotal", "$0.00", "shipping", "$0.00", "tax", "$0.00", "total", "$0.00", "shippingAddress",
                        Map.of()))
                .build();

        // Act
        notificationService.sendOrderConfirmation(context);
        notificationJobProcessor.processAllPending();

        // Assert - Should fall back to English
        List<Mail> sent = mailbox.getMessagesSentTo("test@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Order Confirmation - Thank You for Your Order", email.getSubject());
        assertTrue(email.getHtml().contains("Order Confirmation")); // English header
    }
}
