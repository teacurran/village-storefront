package villagecompute.storefront.storecredit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

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

import villagecompute.storefront.data.models.StoreCreditAccount;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class StoreCreditServiceTest {

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
        tenant.subdomain = "storecredit-test";
        tenant.name = "Store Credit Test Tenant";
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
        user.email = "storecredit@example.com";
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
        if (userId != null) {
            entityManager.createQuery("DELETE FROM User u WHERE u.id = :userId").setParameter("userId", userId)
                    .executeUpdate();
        }
        entityManager.createQuery("DELETE FROM Tenant t WHERE t.id = :tenantId").setParameter("tenantId", tenantId)
                .executeUpdate();
    }

    @Test
    @Transactional
    void adjustCreatesLedgerEntries() {
        StoreCreditTransaction creditTxn = storeCreditService.adjust(userId, new BigDecimal("40.00"), "refund");
        assertEquals(new BigDecimal("40.00"), creditTxn.amount);
        assertEquals("adjusted", creditTxn.transactionType);

        StoreCreditTransaction debitTxn = storeCreditService.adjust(userId, new BigDecimal("-15.00"), "manual debit");
        assertEquals(new BigDecimal("-15.00"), debitTxn.amount);
        assertEquals("redeemed", debitTxn.transactionType);

        StoreCreditAccount account = StoreCreditAccount.findByUser(userId);
        assertEquals(new BigDecimal("25.00"), account.balance);
    }

    @Test
    @Transactional
    void redeemIsIdempotentAndPartial() {
        storeCreditService.adjust(userId, new BigDecimal("30.00"), "seed");

        StoreCreditTransaction first = storeCreditService.redeem(userId, new BigDecimal("50.00"), 111L, "sc-key", null,
                null);
        StoreCreditTransaction second = storeCreditService.redeem(userId, new BigDecimal("50.00"), 111L, "sc-key", null,
                null);

        assertSame(first, second);
        StoreCreditAccount account = StoreCreditAccount.findByUser(userId);
        assertEquals(0, account.balance.compareTo(BigDecimal.ZERO));
        assertEquals("redeemed", first.transactionType);
    }

    @Test
    @Transactional
    void listAccountsFiltersByStatus() {
        storeCreditService.adjust(userId, new BigDecimal("10.00"), "seed");
        StoreCreditAccount account = StoreCreditAccount.findByUser(userId);
        account.status = "suspended";
        entityManager.merge(account);

        List<StoreCreditAccount> suspended = storeCreditService.listAccounts("suspended", 0, 10);
        assertEquals(1, suspended.size());

        List<StoreCreditAccount> active = storeCreditService.listAccounts("active", 0, 10);
        assertEquals(0, active.size());
    }
}
