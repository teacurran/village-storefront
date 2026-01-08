package villagecompute.storefront.giftcard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import villagecompute.storefront.api.types.IssueGiftCardRequest;
import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.GiftCardTransaction;
import villagecompute.storefront.data.models.StoreCreditAccount;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.giftcard.GiftCardService.GiftCardRedemptionResult;
import villagecompute.storefront.storecredit.StoreCreditService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GiftCardServiceTest {

    @Inject
    GiftCardService giftCardService;

    @Inject
    StoreCreditService storeCreditService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    @Transactional
    void setUp() {
        Tenant tenant = new Tenant();
        tenant.subdomain = "giftcard-test";
        tenant.name = "Gift Card Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        User user = new User();
        user.tenant = tenant;
        user.email = "giftcard-user@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @AfterEach
    @Transactional
    void cleanup() {
        if (tenantId == null) {
            return;
        }
        entityManager.createQuery("DELETE FROM StoreCreditTransaction s WHERE s.tenant.id = :tenantId")
                .setParameter("tenantId", tenantId).executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditAccount s WHERE s.tenant.id = :tenantId")
                .setParameter("tenantId", tenantId).executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCardTransaction g WHERE g.tenant.id = :tenantId")
                .setParameter("tenantId", tenantId).executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCard g WHERE g.tenant.id = :tenantId")
                .setParameter("tenantId", tenantId).executeUpdate();
        if (userId != null) {
            entityManager.createQuery("DELETE FROM User u WHERE u.id = :userId").setParameter("userId", userId)
                    .executeUpdate();
        }
        entityManager.createQuery("DELETE FROM Tenant t WHERE t.id = :tenantId").setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Test
    @Transactional
    void issueGiftCardCreatesLedgerEntry() {
        IssueGiftCardRequest request = new IssueGiftCardRequest();
        request.initialBalance = new BigDecimal("150.00");
        request.currency = "USD";
        GiftCard card = giftCardService.issueGiftCard(request);

        assertNotNull(card.id);
        assertNotNull(card.code);
        assertEquals(new BigDecimal("150.00"), card.initialBalance);
        assertEquals(card.initialBalance, card.currentBalance);

        List<GiftCardTransaction> transactions = GiftCardTransaction.find("giftCard.id = ?1", card.id).list();
        assertEquals(1, transactions.size());
        assertEquals(new BigDecimal("150.00"), transactions.get(0).amount);
        assertEquals("issued", transactions.get(0).transactionType);
    }

    @Test
    @Transactional
    void redeemGiftCardSupportsPartialAmount() {
        GiftCard card = createGiftCard(new BigDecimal("100.00"));

        GiftCardRedemptionResult result = giftCardService.redeem(card.code, new BigDecimal("150.00"), 42L, "key-123",
                null, null);

        assertTrue(result.partial());
        assertEquals(new BigDecimal("100.00"), result.amountRedeemed());
        assertEquals(0, result.remainingBalance().compareTo(BigDecimal.ZERO));
        GiftCard updated = GiftCard.findById(card.id);
        assertEquals("redeemed", updated.status);
    }

    @Test
    @Transactional
    void redeemGiftCardIsIdempotent() {
        GiftCard card = createGiftCard(new BigDecimal("80.00"));

        GiftCardRedemptionResult first = giftCardService.redeem(card.code, new BigDecimal("30.00"), 99L, "dup-key",
                null, null);
        GiftCardRedemptionResult second = giftCardService.redeem(card.code, new BigDecimal("30.00"), 99L, "dup-key",
                null, null);

        assertSame(first.transaction(), second.transaction());
        GiftCard updated = GiftCard.findById(card.id);
        assertEquals(new BigDecimal("50.00"), updated.currentBalance);
    }

    @Test
    @Transactional
    void convertToStoreCreditMovesBalance() {
        GiftCard card = createGiftCard(new BigDecimal("60.00"));

        GiftCardService.GiftCardConversionResult conversion = giftCardService.convertToStoreCredit(card.id, userId,
                "customer request");

        GiftCard updated = GiftCard.findById(card.id);
        assertEquals(BigDecimal.ZERO, updated.currentBalance);
        assertEquals("redeemed", updated.status);

        StoreCreditAccount account = StoreCreditAccount.findByUser(userId);
        assertNotNull(account);
        assertEquals(new BigDecimal("60.00"), account.balance);

        StoreCreditTransaction creditTxn = conversion.storeCreditTransaction();
        assertEquals(new BigDecimal("60.00"), creditTxn.amount);
        assertEquals("converted", creditTxn.transactionType);
    }

    private GiftCard createGiftCard(BigDecimal amount) {
        IssueGiftCardRequest request = new IssueGiftCardRequest();
        request.initialBalance = amount;
        request.currency = "USD";
        return giftCardService.issueGiftCard(request);
    }
}
