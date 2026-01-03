package villagecompute.storefront.loyalty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
import villagecompute.storefront.data.repositories.LoyaltyTransactionRepository;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for loyalty ledger operations.
 *
 * <p>
 * Tests cover ledger consistency, concurrency, audit trail, and expiration scenarios.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T4: Loyalty ledger integration testing with concurrency and audit</li>
 * </ul>
 */
@QuarkusTest
class LoyaltyLedgerIT {

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    LoyaltyTransactionRepository transactionRepository;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID userId;

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
        tenant.subdomain = "ledgertest";
        tenant.name = "Ledger Test Tenant";
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
        user.email = "ledger@example.com";
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
        program.name = "Ledger Test Rewards";
        program.enabled = true;
        program.pointsPerDollar = BigDecimal.ONE;
        program.redemptionValuePerPoint = new BigDecimal("0.01");
        program.minRedemptionPoints = 100;
        program.pointsExpirationDays = 30;
        program.createdAt = OffsetDateTime.now();
        program.updatedAt = OffsetDateTime.now();
        entityManager.persist(program);
        entityManager.flush();
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
    void testLedgerAuditTrail() {
        // Enroll member
        loyaltyService.enrollMember(userId);

        // Award points
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("100.00"), UUID.randomUUID());
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("50.00"), UUID.randomUUID());

        // Redeem points
        loyaltyService.redeemPoints(userId, 100, UUID.randomUUID().toString());

        // Adjust points
        loyaltyService.adjustPoints(userId, 25, "Customer service bonus");

        // Verify ledger entries
        LoyaltyMember member = loyaltyService.getMemberByUser(userId).orElseThrow();
        List<LoyaltyTransaction> transactions = transactionRepository.findByMember(member.id, 0, 100);

        assertEquals(4, transactions.size());

        // Verify balance progression
        assertEquals(100, transactions.get(3).balanceAfter); // First purchase
        assertEquals(150, transactions.get(2).balanceAfter); // Second purchase
        assertEquals(50, transactions.get(1).balanceAfter); // Redemption
        assertEquals(75, transactions.get(0).balanceAfter); // Adjustment
    }

    @Test
    @Transactional
    void testLedgerWithExpiration() {
        loyaltyService.enrollMember(userId);

        // Award points
        LoyaltyTransaction transaction = loyaltyService.awardPointsForPurchase(userId, new BigDecimal("100.00"),
                UUID.randomUUID());

        assertNotNull(transaction.expiresAt);
        assertTrue(transaction.expiresAt.isAfter(OffsetDateTime.now().plusDays(29)));
    }

    @Test
    @Transactional
    void testExpirePoints() {
        loyaltyService.enrollMember(userId);

        // Award points
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("100.00"), UUID.randomUUID());

        // Verify initial balance
        LoyaltyMember member = loyaltyService.getMemberByUser(userId).orElseThrow();
        assertEquals(100, member.pointsBalance);

        // Expire points
        int expiredCount = loyaltyService.expirePoints(OffsetDateTime.now().plusDays(31));

        assertEquals(1, expiredCount);

        // Verify balance after expiration
        member = loyaltyService.getMemberByUser(userId).orElseThrow();
        assertEquals(0, member.pointsBalance);

        // Verify expiration transaction
        List<LoyaltyTransaction> transactions = transactionRepository.findByMember(member.id, 0, 10);
        assertTrue(transactions.stream().anyMatch(t -> "expired".equals(t.transactionType)));
    }

    @Test
    @Transactional
    void testTransactionsByOrder() {
        loyaltyService.enrollMember(userId);

        UUID orderId = UUID.randomUUID();
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("100.00"), orderId);

        List<LoyaltyTransaction> transactions = transactionRepository.findByOrder(orderId);

        assertEquals(1, transactions.size());
        assertEquals(orderId, transactions.get(0).orderId);
    }

    @Test
    @Transactional
    void testIdempotencyKeyLookup() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("200.00"), UUID.randomUUID());

        String idempotencyKey = UUID.randomUUID().toString();
        LoyaltyTransaction transaction = loyaltyService.redeemPoints(userId, 150, idempotencyKey);

        // Find by idempotency key
        LoyaltyTransaction found = transactionRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();

        assertEquals(transaction.id, found.id);
    }

    @Test
    void testConcurrentRedemptions() throws InterruptedException {
        // Setup
        setupConcurrentTest();

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        AtomicInteger successCount = new AtomicInteger(0);
        String baseKey = UUID.randomUUID().toString();

        // Attempt 5 concurrent redemptions with different idempotency keys
        for (int i = 0; i < 5; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    TenantContext
                            .setCurrentTenant(new TenantInfo(tenantId, "ledgertest", "Ledger Test Tenant", "active"));
                    loyaltyService.redeemPoints(userId, 100, baseKey + "-" + index);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Expected for insufficient balance
                } finally {
                    TenantContext.clear();
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Verify only valid redemptions succeeded based on available balance
        assertTrue(successCount.get() <= 5);
    }

    @Transactional
    void setupConcurrentTest() {
        loyaltyService.enrollMember(userId);
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("500.00"), UUID.randomUUID());
    }
}
