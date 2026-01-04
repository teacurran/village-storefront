package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import io.smallrye.mutiny.Uni;

/**
 * Plain JVM test verifying the cache-heavy code paths of {@link FeatureToggle}. We mock the cache manager to force
 * execution of the lambda branches JaCoCo tracks separately from our Quarkus integration tests.
 */
class FeatureToggleCacheUnitTest {

    private FeatureToggle featureToggle;
    private CacheManager cacheManager;
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        entityManager = mock(EntityManager.class);

        featureToggle = new FeatureToggle();
        featureToggle.cacheManager = cacheManager;
        featureToggle.entityManager = entityManager;

        TenantContext.setCurrentTenant(new TenantInfo(UUID.randomUUID(), "cache-unit", "Cache Unit Tenant", "active"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesCacheAndInvalidationsWhenAvailable() {
        RecordingCache cache = new RecordingCache("feature-flag-cache");
        when(cacheManager.getCache("feature-flag-cache")).thenReturn(Optional.of(cache));

        TypedQuery<Boolean> tenantQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(startsWith("SELECT ff.enabled FROM FeatureFlag ff WHERE ff.tenant.id"),
                eq(Boolean.class))).thenReturn(tenantQuery);
        when(tenantQuery.setParameter(eq("tenantId"), any())).thenReturn(tenantQuery);
        when(tenantQuery.setParameter(eq("flagKey"), anyString())).thenReturn(tenantQuery);
        when(tenantQuery.setMaxResults(anyInt())).thenReturn(tenantQuery);
        when(tenantQuery.getSingleResult()).thenReturn(Boolean.TRUE, Boolean.FALSE);

        TypedQuery<Boolean> globalQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(startsWith("SELECT ff.enabled FROM FeatureFlag ff WHERE ff.tenant IS NULL"),
                eq(Boolean.class))).thenReturn(globalQuery);
        when(globalQuery.setParameter(eq("flagKey"), anyString())).thenReturn(globalQuery);
        when(globalQuery.setMaxResults(anyInt())).thenReturn(globalQuery);
        when(globalQuery.getSingleResult()).thenThrow(new NoResultException());

        // First call loads value via cache loader (true). Second call should hit cache even though loader could return
        // false.
        assertTrue(featureToggle.isEnabled("cached-flag"));
        assertTrue(featureToggle.isEnabled("cached-flag"));

        // After invalidation we expect the updated database state (false) to be returned.
        featureToggle.invalidate(TenantContext.getCurrentTenantId(), "cached-flag");
        assertFalse(featureToggle.isEnabled("cached-flag"));
    }

    /**
     * Minimal in-memory cache implementation that satisfies the {@link Cache} contract for unit testing.
     */
    static final class RecordingCache implements Cache {
        private final String name;
        private final ConcurrentMap<Object, Object> store = new ConcurrentHashMap<>();

        RecordingCache(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Object getDefaultKey() {
            return "<default>";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> Uni<V> get(K key, Function<K, V> valueLoader) {
            V value = (V) store.computeIfAbsent(key, k -> valueLoader.apply((K) k));
            return Uni.createFrom().item(value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> Uni<V> getAsync(K key, Function<K, Uni<V>> valueLoader) {
            return valueLoader.apply(key).invoke(val -> store.put(key, val));
        }

        @Override
        public Uni<Void> invalidate(Object key) {
            store.remove(key);
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> invalidateAll() {
            store.clear();
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> invalidateIf(Predicate<Object> predicate) {
            store.keySet().removeIf(predicate::test);
            return Uni.createFrom().voidItem();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Cache> T as(Class<T> type) {
            if (type.isInstance(this)) {
                return (T) this;
            }
            throw new IllegalArgumentException("Unsupported cache type: " + type);
        }
    }
}
