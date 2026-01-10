package villagecompute.storefront.loyalty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Fuzz testing for {@link LoyaltyService}.
 *
 * <p>
 * Tests loyalty service with random purchase amounts, concurrent operations, and stress scenarios to ensure ledger
 * integrity under high load and edge cases.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T1: Loyalty module fuzz testing requirement</li>
 * <li>Acceptance Criteria: Points accrue/reserve/refund flows tested with random scenarios</li>
 * </ul>
 */
@QuarkusTest
class LoyaltyFuzzTest {

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    EntityManager entityManager;

    private UUID tenantId;
    private UUID userId;
    private UUID programId;
    private LoyaltyProgram program;

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
        tenant.subdomain = "fuzztest";
        tenant.name = "Fuzz Test Tenant";
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
        user.email = "fuzztest@example.com";
        user.status = "active";
        user.emailVerified = true;
        user.createdAt = OffsetDateTime.now();
        user.updatedAt = OffsetDateTime.now();
        entityManager.persist(user);
        entityManager.flush();
        userId = user.id;

        // Create loyalty program with tier multipliers
        program = new LoyaltyProgram();
        program.tenant = tenant;
        program.name = "Fuzz Test Rewards";
        program.description = "Fuzz testing loyalty program";
        program.enabled = true;
        program.pointsPerDollar = BigDecimal.ONE;
        program.redemptionValuePerPoint = new BigDecimal("0.01");
        program.minRedemptionPoints = 100;
        program.maxRedemptionPoints = 10000;
        program.pointsExpirationDays = 365;
        program.tierConfig = "[{\"name\":\"Bronze\",\"minPoints\":0,\"multiplier\":1.0},{\"name\":\"Silver\",\"minPoints\":1000,\"multiplier\":1.5},{\"name\":\"Gold\",\"minPoints\":5000,\"multiplier\":2.0}]";
        program.createdAt = OffsetDateTime.now();
        program.updatedAt = OffsetDateTime.now();
        entityManager.persist(program);
        entityManager.flush();
        programId = program.id;

        // Enroll user in program
        loyaltyService.enrollMember(userId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Test points accrual with random purchase amounts.
     *
     * <p>
     * Generates 1000 random purchase amounts between $0.01 and $10,000 and verifies that points are calculated
     * correctly using the program's points-per-dollar rate.
     */
    @Test
    @Transactional
    void testRandomPurchaseAmounts() {
        Random random = new Random(42); // Fixed seed for reproducibility
        int testCount = 1000;
        int totalPointsAwarded = 0;

        for (int i = 0; i < testCount; i++) {
            // Generate random purchase amount between $0.01 and $10,000.00
            BigDecimal amount = BigDecimal.valueOf(random.nextDouble() * 10000).setScale(2, RoundingMode.HALF_UP);

            if (amount.compareTo(new BigDecimal("0.01")) < 0) {
                amount = new BigDecimal("0.01");
            }

            Optional<LoyaltyTransaction> tx = loyaltyService.awardPointsForPurchase(userId, amount, UUID.randomUUID());

            if (tx.isPresent()) {
                // Verify points calculation matches expected formula
                // Note: Tier multipliers may apply, so we need to get current tier
                LoyaltyMember member = loyaltyService.getMemberByUser(userId).orElseThrow();
                BigDecimal effectiveMultiplier = getMultiplierForTier(member.currentTier);

                int expectedPoints = amount.multiply(program.pointsPerDollar).multiply(effectiveMultiplier)
                        .setScale(0, RoundingMode.DOWN).intValue();

                assertEquals(expectedPoints, tx.get().pointsDelta,
                        String.format("Points mismatch for amount %s (tier=%s, multiplier=%s)", amount,
                                member.currentTier, effectiveMultiplier));

                totalPointsAwarded += tx.get().pointsDelta;
            }
        }

        // Verify final balance matches sum of all transactions
        LoyaltyMember finalMember = loyaltyService.getMemberByUser(userId).orElseThrow();
        assertEquals(totalPointsAwarded, finalMember.pointsBalance, "Final balance should match sum of awarded points");

        // Verify ledger integrity
        List<LoyaltyTransaction> transactions = loyaltyService.getTransactionHistory(userId, 0, testCount + 1);
        int calculatedBalance = transactions.stream().mapToInt(tx -> tx.pointsDelta).sum();

        assertEquals(calculatedBalance, finalMember.pointsBalance, "Balance should match transaction ledger sum");
    }

    /**
     * Test concurrent accrual and redemption operations.
     *
     * <p>
     * Spawns 100 concurrent threads that randomly perform either points accrual or redemption. Verifies that the final
     * balance matches the transaction ledger and no points are lost or duplicated.
     */
    @Test
    void testConcurrentOperations() throws InterruptedException {
        Random random = new Random(123); // Fixed seed for reproducibility
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successfulAccruals = new AtomicInteger(0);
        AtomicInteger successfulRedemptions = new AtomicInteger(0);
        AtomicInteger failedRedemptions = new AtomicInteger(0);

        // First, seed account with points
        for (int i = 0; i < 20; i++) {
            loyaltyService.awardPointsForPurchase(userId, BigDecimal.valueOf(100), UUID.randomUUID());
        }

        // Now run concurrent operations
        for (int i = 0; i < threadCount; i++) {
            final int iteration = i;
            executor.submit(() -> {
                try {
                    // Randomly choose accrual or redemption
                    boolean isAccrual = random.nextBoolean();

                    if (isAccrual) {
                        // Award points for random purchase
                        BigDecimal amount = BigDecimal.valueOf(50 + random.nextInt(200));
                        Optional<LoyaltyTransaction> tx = loyaltyService.awardPointsForPurchase(userId, amount,
                                UUID.randomUUID());
                        if (tx.isPresent()) {
                            successfulAccruals.incrementAndGet();
                        }
                    } else {
                        // Attempt to redeem random points
                        int pointsToRedeem = 10 + random.nextInt(90); // 10-100 points
                        try {
                            loyaltyService.redeemPoints(userId, pointsToRedeem, "concurrent-test-" + iteration);
                            successfulRedemptions.incrementAndGet();
                        } catch (IllegalArgumentException e) {
                            // Expected: insufficient balance
                            failedRedemptions.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all operations to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent operations should complete within 30 seconds");
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Verify final balance matches transaction log
        LoyaltyMember finalMember = loyaltyService.getMemberByUser(userId).orElseThrow();
        List<LoyaltyTransaction> transactions = loyaltyService.getTransactionHistory(userId, 0, 1000);

        int calculatedBalance = transactions.stream().mapToInt(tx -> tx.pointsDelta).sum();

        assertEquals(calculatedBalance, finalMember.pointsBalance,
                "Final balance should match transaction ledger sum after concurrent operations");

        // Balance should never go negative
        assertTrue(finalMember.pointsBalance >= 0, "Balance should never be negative");

        // Log test summary
        System.out.printf("Concurrent test completed - accruals=%d, redemptions=%d, failedRedemptions=%d%n",
                successfulAccruals.get(), successfulRedemptions.get(), failedRedemptions.get());
    }

    /**
     * Test ledger integrity under stress.
     *
     * <p>
     * Performs 500 sequential operations (accrual + redemption cycles) and verifies that the ledger remains consistent
     * and all transactions are properly recorded.
     */
    @Test
    @Transactional
    void testLedgerIntegrityUnderStress() {
        Random random = new Random(789);
        int cycleCount = 500;
        List<Integer> balanceSnapshots = new ArrayList<>();

        for (int i = 0; i < cycleCount; i++) {
            // Award points
            BigDecimal amount = BigDecimal.valueOf(10 + random.nextInt(100));
            loyaltyService.awardPointsForPurchase(userId, amount, UUID.randomUUID());

            // Record balance
            LoyaltyMember member = loyaltyService.getMemberByUser(userId).orElseThrow();
            balanceSnapshots.add(member.pointsBalance);

            // Attempt redemption (may fail if insufficient balance)
            if (member.pointsBalance >= 100) {
                int pointsToRedeem = 50 + random.nextInt(Math.min(member.pointsBalance - 99, 200));
                try {
                    loyaltyService.redeemPoints(userId, pointsToRedeem, "stress-test-" + i);
                } catch (IllegalArgumentException e) {
                    // Expected if redemption exceeds balance
                }
            }
        }

        // Verify final state
        LoyaltyMember finalMember = loyaltyService.getMemberByUser(userId).orElseThrow();
        List<LoyaltyTransaction> allTransactions = loyaltyService.getTransactionHistory(userId, 0, cycleCount * 2);

        // Calculate balance from ledger
        int ledgerBalance = allTransactions.stream().mapToInt(tx -> tx.pointsDelta).sum();

        assertEquals(ledgerBalance, finalMember.pointsBalance,
                "Ledger balance should match member balance after stress test");

        // Verify balanceAfter snapshots are consistent
        for (LoyaltyTransaction tx : allTransactions) {
            assertNotNull(tx.balanceAfter, "Every transaction should have balanceAfter snapshot");
            assertTrue(tx.balanceAfter >= 0, "balanceAfter should never be negative");
        }

        // Verify no duplicate transactions (idempotency)
        long uniqueTransactions = allTransactions.stream().map(tx -> tx.id).distinct().count();
        assertEquals(allTransactions.size(), uniqueTransactions, "All transactions should have unique IDs");
    }

    /**
     * Test edge case purchase amounts.
     *
     * <p>
     * Tests boundary conditions like $0.00, $0.01, very large amounts, and negative amounts to ensure robust handling.
     */
    @Test
    @Transactional
    void testEdgeCasePurchaseAmounts() {
        // Test $0.00 (should not award points)
        Optional<LoyaltyTransaction> zeroTx = loyaltyService.awardPointsForPurchase(userId, BigDecimal.ZERO,
                UUID.randomUUID());
        assertTrue(zeroTx.isEmpty(), "Zero amount should not award points");

        // Test $0.01 (minimum positive amount)
        Optional<LoyaltyTransaction> oneCentTx = loyaltyService.awardPointsForPurchase(userId, new BigDecimal("0.01"),
                UUID.randomUUID());
        assertTrue(oneCentTx.isEmpty(), "One cent should not award points (rounds down to 0)");

        // Test $1.00 (should award 1 point)
        Optional<LoyaltyTransaction> oneDollarTx = loyaltyService.awardPointsForPurchase(userId, new BigDecimal("1.00"),
                UUID.randomUUID());
        assertTrue(oneDollarTx.isPresent(), "One dollar should award points");
        assertEquals(1, oneDollarTx.get().pointsDelta, "One dollar should award 1 point");

        // Test very large amount ($999,999.99)
        Optional<LoyaltyTransaction> largeTx = loyaltyService.awardPointsForPurchase(userId,
                new BigDecimal("999999.99"), UUID.randomUUID());
        assertTrue(largeTx.isPresent(), "Large amount should award points");
        assertTrue(largeTx.get().pointsDelta >= 999999, "Large amount should award proportional points");

        // Test negative amount (should fail gracefully)
        try {
            loyaltyService.awardPointsForPurchase(userId, new BigDecimal("-100.00"), UUID.randomUUID());
            // Should throw exception or return empty
        } catch (IllegalArgumentException e) {
            // Expected behavior
        }
    }

    /**
     * Test redemption idempotency under concurrent requests.
     *
     * <p>
     * Attempts to redeem the same points multiple times with the same idempotency key and verifies that only one
     * transaction is created.
     */
    @Test
    void testRedemptionIdempotency() throws InterruptedException {
        // Award initial points
        loyaltyService.awardPointsForPurchase(userId, new BigDecimal("1000"), UUID.randomUUID());

        String idempotencyKey = "test-idempotency-" + UUID.randomUUID();
        int pointsToRedeem = 500;
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        // Attempt same redemption from multiple threads
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    LoyaltyTransaction tx = loyaltyService.redeemPoints(userId, pointsToRedeem, idempotencyKey);
                    if (tx != null) {
                        successCount.incrementAndGet();
                    }
                } catch (IllegalArgumentException e) {
                    // May be duplicate or insufficient balance
                    duplicateCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Idempotency test should complete within 30 seconds");
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // Only one redemption should succeed
        assertEquals(1, successCount.get(), "Only one redemption should succeed with same idempotency key");

        // Verify only one transaction was created
        List<LoyaltyTransaction> transactions = loyaltyService.getTransactionHistory(userId, 0, 100);
        long redemptionCount = transactions.stream().filter(tx -> "redeemed".equals(tx.transactionType)).count();
        assertEquals(1, redemptionCount, "Only one redemption transaction should exist");
    }

    // Helper method to get tier multiplier
    private BigDecimal getMultiplierForTier(String tierName) {
        if (tierName == null) {
            return BigDecimal.ONE;
        }
        return switch (tierName) {
            case "Bronze" -> BigDecimal.ONE;
            case "Silver" -> new BigDecimal("1.5");
            case "Gold" -> new BigDecimal("2.0");
            default -> BigDecimal.ONE;
        };
    }
}
