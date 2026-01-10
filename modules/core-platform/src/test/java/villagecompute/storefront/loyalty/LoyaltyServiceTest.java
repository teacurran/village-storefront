package villagecompute.storefront.loyalty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.LoyaltyMember;
import villagecompute.storefront.data.models.LoyaltyProgram;
import villagecompute.storefront.data.models.LoyaltyTransaction;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link LoyaltyService}.
 *
 * <p>
 * Tests cover enrollment, points accrual, redemption, tier calculations, and tenant isolation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty service testing with accrual and redemption scenarios</li>
 * </ul>
 */
@QuarkusTest
class LoyaltyServiceTest {

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID userId;
    private UUID programId;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean up existing data
        entityManager.createQuery("DELETE FROM LoyaltyTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM LoyaltyMember").executeUpdate();
        entityManager.createQuery("DELETE FROM LoyaltyProgram").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();

        // Create test tenant
        Tenant tenant = new Tenant();
        tenant.subdomain = "loyaltytest";
        tenant.name = "Loyalty Test Tenant";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);
        entityManager.flush();
        tenantId = tenant.id;

        // Set current tenant context
        TenantContext.setCurrentTenant(new TenantInfo(tenantId, tenant.subdomain, tenant.name, tenant.status));

        // Create test user
        User user = new User();
        user.tenant = tenant;
        user.email = "test@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;

        // Create loyalty program
        LoyaltyProgram program = new LoyaltyProgram();
        program.tenant = tenant;
        program.name = "Test Rewards";
        program.description = "Test loyalty program";
        program.enabled = true;
        program.pointsPerDollar = BigDecimal.ONE;
        program.redemptionValuePerPoint = new BigDecimal("0.01");
        program.minRedemptionPoints = 100;
        program.maxRedemptionPoints = 10000;
        program.pointsExpirationDays = 365;
        program.createdAt = OffsetDateTime.now();
        program.updatedAt = OffsetDateTime.now();
        entityManager.persist(program);
        entityManager.flush();
        programId = program.id;
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @AfterEach
    @Transactional
    void cleanupData() {
        entityManager.createQuery("DELETE FROM LoyaltyTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM LoyaltyMember").executeUpdate();
        entityManager.createQuery("DELETE FROM LoyaltyProgram").executeUpdate();
        if (userId != null) {
            entityManager.createQuery("DELETE FROM User u WHERE u.id = :userId").setParameter("userId", userId)
                    .executeUpdate();
        }
        if (tenantId != null) {
            entityManager.createQuery("DELETE FROM Tenant t WHERE t.id = :tenantId").setParameter("tenantId", tenantId)
                    .executeUpdate();
        }
    }

    @Test
    @Transactional
    void testGetActiveProgram() {
        Optional<LoyaltyProgram> program = loyaltyService.getActiveProgram();
        assertTrue(program.isPresent());
        assertEquals("Test Rewards", program.get().name);
        assertEquals(tenantId, program.get().tenant.id);
    }

    @Test
    @Transactional
    void testEnrollMember() {
        LoyaltyMember member = loyaltyService.enrollMember(userId);

        assertNotNull(member);
        assertNotNull(member.id);
        assertEquals(userId, member.user.id);
        assertEquals(programId, member.program.id);
        assertEquals(0, member.pointsBalance);
        assertEquals(0, member.lifetimePointsEarned);
        assertEquals("active", member.status);
        assertNotNull(member.currentTier);
    }

    @Test
    @Transactional
    void testEnrollMemberTwice() {
        LoyaltyMember member1 = loyaltyService.enrollMember(userId);
        LoyaltyMember member2 = loyaltyService.enrollMember(userId);

        // Should return same member
        assertEquals(member1.id, member2.id);
    }

    @Test
    @Transactional
    void testAwardPointsForPurchase() {
        loyaltyService.enrollMember(userId);

        UUID orderId = UUID.randomUUID();
        BigDecimal purchaseAmount = new BigDecimal("100.00");

        LoyaltyTransaction transaction = loyaltyService.awardPointsForPurchase(userId, purchaseAmount, orderId)
                .orElseThrow();

        assertNotNull(transaction);
        assertEquals(100, transaction.pointsDelta);
        assertEquals(100, transaction.balanceAfter);
        assertEquals("earned", transaction.transactionType);
        assertEquals(orderId, transaction.orderId);
        assertNotNull(transaction.expiresAt);

        // Verify member balance
        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals(100, member.get().pointsBalance);
        assertEquals(100, member.get().lifetimePointsEarned);
    }

    @Test
    @Transactional
    void testAwardPointsAutoEnroll() {
        // Should auto-enroll if not already enrolled
        UUID orderId = UUID.randomUUID();
        BigDecimal purchaseAmount = new BigDecimal("50.00");

        LoyaltyTransaction transaction = loyaltyService.awardPointsForPurchase(userId, purchaseAmount, orderId)
                .orElseThrow();

        assertNotNull(transaction);
        assertEquals(50, transaction.pointsDelta);

        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
    }

    @Test
    @Transactional
    void testAwardPointsReturnsEmptyForSmallPurchase() {
        loyaltyService.enrollMember(userId);

        Optional<LoyaltyTransaction> transaction = loyaltyService.awardPointsForPurchase(userId, new BigDecimal("0.50"),
                UUID.randomUUID());

        assertTrue(transaction.isEmpty());

        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals(0, member.get().pointsBalance);
        assertEquals(0, member.get().lifetimePointsEarned);
    }

    @Test
    @Transactional
    void testRedeemPoints() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("200.00"), UUID.randomUUID()).orElseThrow();

        String idempotencyKey = UUID.randomUUID().toString();
        LoyaltyTransaction transaction = loyaltyService.redeemPoints(userId, 150, idempotencyKey);

        assertNotNull(transaction);
        assertEquals(-150, transaction.pointsDelta);
        assertEquals(50, transaction.balanceAfter);
        assertEquals("redeemed", transaction.transactionType);
        assertEquals(idempotencyKey, transaction.idempotencyKey);

        // Verify member balance
        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals(50, member.get().pointsBalance);
        assertEquals(150, member.get().lifetimePointsRedeemed);
    }

    @Test
    @Transactional
    void testRedeemPointsIdempotent() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("200.00"), UUID.randomUUID()).orElseThrow();

        String idempotencyKey = UUID.randomUUID().toString();
        LoyaltyTransaction transaction1 = loyaltyService.redeemPoints(userId, 150, idempotencyKey);
        LoyaltyTransaction transaction2 = loyaltyService.redeemPoints(userId, 150, idempotencyKey);

        // Should return same transaction
        assertEquals(transaction1.id, transaction2.id);

        // Balance should only be debited once
        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals(50, member.get().pointsBalance);
    }

    @Test
    @Transactional
    void testRedeemPointsInsufficientBalance() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("50.00"), UUID.randomUUID()).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> {
            loyaltyService.redeemPoints(userId, 100, UUID.randomUUID().toString());
        });
    }

    @Test
    @Transactional
    void testRedeemPointsBelowMinimum() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("200.00"), UUID.randomUUID()).orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> {
            loyaltyService.redeemPoints(userId, 50, UUID.randomUUID().toString());
        });
    }

    @Test
    @Transactional
    void testAdjustPoints() {
        loyaltyService.enrollMember(userId);

        LoyaltyTransaction transaction = loyaltyService.adjustPoints(userId, 500, "Birthday bonus");

        assertNotNull(transaction);
        assertEquals(500, transaction.pointsDelta);
        assertEquals(500, transaction.balanceAfter);
        assertEquals("adjusted", transaction.transactionType);
        assertEquals("Birthday bonus", transaction.reason);

        // Verify member balance
        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals(500, member.get().pointsBalance);
        assertEquals(500, member.get().lifetimePointsEarned);
    }

    @Test
    @Transactional
    void testTierCalculation() {
        loyaltyService.enrollMember(userId);

        // Award small amount - should be Bronze
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("500.00"), UUID.randomUUID()).orElseThrow();
        Optional<LoyaltyMember> member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals("Bronze", member.get().currentTier);

        // Award more - should upgrade to Silver
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("600.00"), UUID.randomUUID()).orElseThrow();
        member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals("Silver", member.get().currentTier);

        // Award even more - should upgrade to Gold
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("4000.00"), UUID.randomUUID()).orElseThrow();
        member = loyaltyService.getMemberByUser(userId);
        assertTrue(member.isPresent());
        assertEquals("Gold", member.get().currentTier);
    }

    @Test
    @Transactional
    void testGetTransactionHistory() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("100.00"), UUID.randomUUID()).orElseThrow();
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("50.00"), UUID.randomUUID()).orElseThrow();
        loyaltyService.redeemPoints(userId, 100, UUID.randomUUID().toString());

        List<LoyaltyTransaction> transactions = loyaltyService.getTransactionHistory(userId, 0, 10);

        assertNotNull(transactions);
        assertEquals(3, transactions.size());
    }

    @Test
    @Transactional
    void testCalculateCartSummary() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("200.00"), UUID.randomUUID()).orElseThrow();

        CartLoyaltyProjection projection = loyaltyService.calculateCartSummary(new BigDecimal("50.00"), userId);

        assertTrue(projection.isProgramEnabled());
        assertEquals(200, projection.getMemberPointsBalance());
        assertEquals(50, projection.getEstimatedPointsEarned());
        assertNotNull(projection.getEstimatedRewardValue());
        assertNotNull(projection.getCurrentTier());
    }

    @Test
    @Transactional
    void testAdjustPointsCannotOverdraw() {
        loyaltyService.enrollMember(userId);
        loyaltyService.adjustPoints(userId, 100, "Seed");

        assertThrows(IllegalArgumentException.class, () -> loyaltyService.adjustPoints(userId, -150, "Invalid"));
    }

    @Test
    @Transactional
    void testCalculateRedemptionValue() {
        BigDecimal value = loyaltyService.calculateRedemptionValue(1000);
        assertEquals(new BigDecimal("10.00"), value);
    }
}
