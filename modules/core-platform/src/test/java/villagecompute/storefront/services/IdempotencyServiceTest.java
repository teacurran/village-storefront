package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Tests for {@link IdempotencyService} covering pending conflicts and cached replay behavior.
 */
@QuarkusTest
class IdempotencyServiceTest {

    @Inject
    IdempotencyService idempotencyService;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @Transactional
    void setUp() {
        entityManager.createQuery("DELETE FROM IdempotencyKey").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();

        Tenant tenant = new Tenant();
        tenant.subdomain = "idempotency-test";
        tenant.name = "Idempotency Test";
        tenant.status = "active";
        tenant.settings = "{}";
        tenant.createdAt = OffsetDateTime.now();
        tenant.updatedAt = OffsetDateTime.now();
        entityManager.persist(tenant);

        TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @Transactional
    void acquire_shouldThrowWhenPending() {
        IdempotencyKey key = idempotencyService.acquire("key-pending", "checkout", 5);
        assertEquals(IdempotencyKey.Status.PENDING, key.status);

        assertThrows(IdempotencyConflictException.class,
                () -> idempotencyService.acquire("key-pending", "checkout", 5));
    }

    @Test
    @Transactional
    void acquire_shouldReplayResultAfterSuccess() {
        String key = UUID.randomUUID().toString();
        idempotencyService.acquire(key, "checkout", 5);
        idempotencyService.markSuccess(key, "{\"orderId\":\"123\"}", 200);

        IdempotencyKey cached = idempotencyService.acquire(key, "checkout", 5);
        assertTrue(cached.isSuccess());
        assertEquals("{\"orderId\":\"123\"}", cached.result);
    }
}
