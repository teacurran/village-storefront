package villagecompute.storefront.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Integration tests for {@link NotificationService}.
 *
 * <p>
 * Tests cover email template rendering, i18n placeholders, feature flag gating, tenant isolation, and Mailer
 * integration.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T5: Notification service tests</li>
 * <li>Acceptance Criteria: Templates render with sample data, i18n placeholders resolved, notifications enqueue
 * background jobs</li>
 * </ul>
 */
@QuarkusTest
class NotificationServiceTest {

    private static final List<String> NOTIFICATION_FLAG_KEYS = List.of("notifications.consignor.intake",
            "notifications.consignor.sale", "notifications.consignor.payout", "notifications.consignor.expiration");

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
        // Clean up existing notification feature flags without touching tenants created by other suites
        entityManager.createQuery("DELETE FROM FeatureFlag f WHERE f.flagKey IN :keys")
                .setParameter("keys", NOTIFICATION_FLAG_KEYS).executeUpdate();

        // Create test tenant with unique subdomain per test run
        Tenant tenant = new Tenant();
        tenant.subdomain = "notiftest-" + UUID.randomUUID().toString().substring(0, 8);
        tenant.name = "Notification Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();

        tenantId = tenant.id;
        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

        // Enable all notification feature flags globally
        persistGlobalFlag("notifications.consignor.intake", true);
        persistGlobalFlag("notifications.consignor.sale", true);
        persistGlobalFlag("notifications.consignor.payout", true);
        persistGlobalFlag("notifications.consignor.expiration", true);

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
        flag.owner = "notifications-tests@villagecompute.dev";
        flag.lastReviewedAt = OffsetDateTime.now();
        entityManager.persist(flag);
        entityManager.flush();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        entityManager.createQuery("DELETE FROM FeatureFlag f WHERE f.flagKey IN :keys")
                .setParameter("keys", NOTIFICATION_FLAG_KEYS).executeUpdate();

        if (tenantId != null) {
            entityManager.createQuery("DELETE FROM Tenant t WHERE t.id = :tenantId").setParameter("tenantId", tenantId)
                    .executeUpdate();
            tenantId = null;
        }

        TenantContext.clear();
    }

    @Test
    void testSendIntakeConfirmation_RendersTemplateWithSampleData() {
        // Arrange
        UUID consignorId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
                .consignorName("Jane Doe").consignorEmail("jane@example.com").locale("en")
                .templateData(
                        Map.of("batchId", batchId.toString(), "itemCount", 5, "submittedAt", "2026-01-03 10:30 AM"))
                .build();

        // Act
        notificationService.sendIntakeConfirmation(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("jane@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Intake Confirmation - Items Received", email.getSubject());
        assertTrue(email.getHtml().contains("Jane Doe"));
        assertTrue(email.getHtml().contains(batchId.toString()));
        assertTrue(email.getHtml().contains("5"));
    }

    @Test
    void testSendSaleNotification_RendersTemplateWithI18nPlaceholders() {
        // Arrange
        UUID consignorId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
                .consignorName("Carlos Gómez").consignorEmail("carlos@example.com").locale("es")
                .templateData(Map.of("productName", "Vintage Guitar", "salePrice", "$1,200.00", "commission", "$300.00",
                        "soldAt", "2026-01-03 14:45"))
                .build();

        // Act
        notificationService.sendSaleNotification(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("carlos@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("¡Excelente Noticia - Su Artículo se Vendió!", email.getSubject());
        assertTrue(email.getHtml().contains("Carlos Gómez"));
        assertTrue(email.getHtml().contains("Vintage Guitar"));
        assertTrue(email.getHtml().contains("$1,200.00"));
        assertTrue(email.getHtml().contains("$300.00"));
        // Verify Spanish i18n
        assertTrue(email.getHtml().contains("Artículo Vendido"));
    }

    @Test
    void testSendPayoutSummary_RendersTemplateWithFormattedData() {
        // Arrange
        UUID consignorId = UUID.randomUUID();
        UUID payoutBatchId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
                .consignorName("Alice Smith").consignorEmail("alice@example.com").locale("en")
                .templateData(Map.of("payoutBatchId", payoutBatchId.toString(), "totalAmount", "$2,450.00", "itemsSold",
                        12, "payoutDate", "2026-01-03", "paymentMethod", "Bank Transfer"))
                .build();

        // Act
        notificationService.sendPayoutSummary(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("alice@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Payout Processed - Payment on the Way", email.getSubject());
        assertTrue(email.getHtml().contains("Alice Smith"));
        assertTrue(email.getHtml().contains("$2,450.00"));
        assertTrue(email.getHtml().contains("12"));
        assertTrue(email.getHtml().contains("Bank Transfer"));
    }

    @Test
    void testSendExpirationAlert_RendersTemplateWithItemList() {
        // Arrange
        UUID consignorId = UUID.randomUUID();
        List<Map<String, String>> expiringItems = List.of(Map.of("name", "Item A", "expiryDate", "2026-01-10"),
                Map.of("name", "Item B", "expiryDate", "2026-01-12"));

        NotificationContext context = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
                .consignorName("Bob Johnson").consignorEmail("bob@example.com").locale("en")
                .templateData(Map.of("expiringItems", expiringItems, "expirationDays", 7)).build();

        // Act
        notificationService.sendExpirationAlert(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("bob@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        assertEquals("Action Required - Items Approaching Expiration", email.getSubject());
        assertTrue(email.getHtml().contains("Bob Johnson"));
        assertTrue(email.getHtml().contains("Item A"));
        assertTrue(email.getHtml().contains("Item B"));
        assertTrue(email.getHtml().contains("2026-01-10"));
        assertTrue(email.getHtml().contains("7"));
    }

    @Test
    @Transactional
    void testNotificationSkipped_WhenFeatureFlagDisabled() {
        // Arrange - Disable the intake notification flag for this tenant
        FeatureFlag disabledFlag = new FeatureFlag();
        disabledFlag.tenant = entityManager.getReference(Tenant.class, tenantId);
        disabledFlag.flagKey = "notifications.consignor.intake";
        disabledFlag.enabled = false; // Override global setting
        disabledFlag.config = "{}";
        disabledFlag.createdAt = OffsetDateTime.now();
        disabledFlag.updatedAt = OffsetDateTime.now();
        disabledFlag.owner = "notifications-tests@villagecompute.dev";
        disabledFlag.lastReviewedAt = OffsetDateTime.now();
        entityManager.persist(disabledFlag);
        entityManager.flush();

        // Invalidate cache to pick up new flag value
        featureToggle.invalidateAll();

        UUID consignorId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
                .consignorName("Test User").consignorEmail("test@example.com").locale("en")
                .templateData(Map.of("batchId", UUID.randomUUID().toString(), "itemCount", 1, "submittedAt", "now"))
                .build();

        // Act
        notificationService.sendIntakeConfirmation(context);

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("test@example.com");
        assertEquals(0, sent.size(), "Email should not be sent when feature flag is disabled");
    }

    @Test
    void testTenantIsolation_ThrowsExceptionOnMismatch() {
        // Arrange
        UUID wrongTenantId = UUID.randomUUID();
        UUID consignorId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(wrongTenantId) // Different from
                                                                                            // TenantContext
                .consignorId(consignorId).consignorName("Test User").consignorEmail("test@example.com").locale("en")
                .templateData(Map.of("batchId", UUID.randomUUID().toString(), "itemCount", 1, "submittedAt", "now"))
                .build();

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> notificationService.sendIntakeConfirmation(context),
                "Should throw when tenant context doesn't match");
    }

    @Test
    void testLocaleFallback_EnglishDefault() {
        // Arrange
        UUID consignorId = UUID.randomUUID();
        NotificationContext context = NotificationContext.builder().tenantId(tenantId).consignorId(consignorId)
                .consignorName("Test User").consignorEmail("test@example.com").locale("fr") // Unsupported locale
                .templateData(Map.of("productName", "Test Product", "salePrice", "$100.00", "commission", "$25.00",
                        "soldAt", "now"))
                .build();

        // Act
        notificationService.sendSaleNotification(context);
        notificationJobProcessor.processAllPending();

        // Assert
        List<Mail> sent = mailbox.getMessagesSentTo("test@example.com");
        assertEquals(1, sent.size());

        Mail email = sent.get(0);
        // Should fall back to English
        assertEquals("Great News - Your Item Sold!", email.getSubject());
        assertTrue(email.getHtml().contains("Item Sold")); // English header
    }

    @Test
    void testNotificationContext_ValidatesRequiredFields() {
        // Missing tenantId
        assertThrows(IllegalStateException.class, () -> {
            NotificationContext.builder().consignorId(UUID.randomUUID()).consignorEmail("test@example.com")
                    .templateData(Map.of()).build();
        });

        // Missing consignorId
        assertThrows(IllegalStateException.class, () -> {
            NotificationContext.builder().tenantId(UUID.randomUUID()).consignorEmail("test@example.com")
                    .templateData(Map.of()).build();
        });

        // Missing consignorEmail
        assertThrows(IllegalStateException.class, () -> {
            NotificationContext.builder().tenantId(UUID.randomUUID()).consignorId(UUID.randomUUID())
                    .templateData(Map.of()).build();
        });

        // Missing templateData
        assertThrows(IllegalStateException.class, () -> {
            NotificationContext.builder().tenantId(UUID.randomUUID()).consignorId(UUID.randomUUID())
                    .consignorEmail("test@example.com").build();
        });
    }
}
