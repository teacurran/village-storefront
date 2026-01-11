package villagecompute.storefront.services.jobs;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Cart;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job for cleaning up expired carts.
 *
 * <p>
 * Periodically scans and deletes carts that have passed their expiration timestamp across all active tenants. This
 * prevents database bloat from abandoned shopping carts.
 *
 * <p>
 * Carts have a default 30-day TTL set at creation time (see {@link Cart#prePersist()}). This scheduler runs daily at 2
 * AM to identify and purge expired entries along with their associated cart items (via cascade delete).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Cart expiration via scheduler hook</li>
 * <li>ERD: carts table with expires_at column (docs/diagrams/domain_erd.puml)</li>
 * <li>Architecture: Cart lifecycle management (docs/architecture/02_System_Structure_and_Data.md)</li>
 * </ul>
 */
@ApplicationScoped
public class CartExpirationScheduler {

    private static final Logger LOG = Logger.getLogger(CartExpirationScheduler.class);

    @Inject
    EntityManager entityManager;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(
            name = "cart.expiration.enabled",
            defaultValue = "true")
    boolean cartExpirationEnabled;

    @ConfigProperty(
            name = "cart.expiration.batch-size",
            defaultValue = "1000")
    int batchSize;

    /**
     * Delete expired carts daily at 2 AM.
     *
     * <p>
     * Respects feature flag gating via {@code cart.expiration.enabled} property. Emits metrics (duration, carts
     * deleted) per tenant for observability.
     *
     * <p>
     * Uses batched deletion to avoid transaction timeout on large datasets. Cascade deletes associated cart_items via
     * JPA relationship.
     */
    @Scheduled(
            cron = "${cart.expiration.cron:0 0 2 * * ?}",
            identity = "cart-expiration")
    @Transactional
    public void deleteExpiredCarts() {
        if (!cartExpirationEnabled) {
            LOG.debug("Cart expiration disabled via feature flag, skipping cleanup");
            return;
        }

        LOG.info("Starting expired cart cleanup");
        Instant startTime = Instant.now();

        try {
            List<Tenant> tenants = Tenant.find("status", "active").list();
            LOG.infof("Cleaning expired carts for %d active tenants", tenants.size());

            int totalDeleted = 0;

            for (Tenant tenant : tenants) {
                try {
                    TenantContext
                            .setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

                    Instant tenantStartTime = Instant.now();
                    int tenantDeleted = deleteExpiredCartsForTenant(tenant.id);
                    Duration tenantDuration = Duration.between(tenantStartTime, Instant.now());

                    totalDeleted += tenantDeleted;

                    // Record SLA metrics per tenant
                    meterRegistry.timer("cart.expiration.duration", "tenant_id", tenant.id.toString())
                            .record(tenantDuration);
                    meterRegistry.counter("cart.expiration.deleted", "tenant_id", tenant.id.toString())
                            .increment(tenantDeleted);

                    if (tenantDeleted > 0) {
                        LOG.infof("Expired carts deleted - tenantId=%s, deletedCount=%d, duration=%dms", tenant.id,
                                tenantDeleted, tenantDuration.toMillis());
                    }

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to delete expired carts for tenant %s", tenant.id);
                    meterRegistry.counter("cart.expiration.errors", "tenant_id", tenant.id.toString()).increment();
                } finally {
                    TenantContext.clear();
                }
            }

            Duration totalDuration = Duration.between(startTime, Instant.now());
            LOG.infof("Completed expired cart cleanup - tenants=%d, deleted=%d, duration=%dms", tenants.size(),
                    totalDeleted, totalDuration.toMillis());

            // Record global metrics
            meterRegistry.timer("cart.expiration.duration_global").record(totalDuration);
            meterRegistry.counter("cart.expiration.deleted_global").increment(totalDeleted);

        } catch (Exception e) {
            LOG.error("Failed to complete expired cart cleanup", e);
            meterRegistry.counter("cart.expiration.errors_global").increment();
        }
    }

    /**
     * Delete expired carts for a single tenant in batches.
     *
     * <p>
     * Uses JPQL bulk delete with batch processing to avoid loading entities into memory. Cart items are cascade deleted
     * via foreign key constraints.
     *
     * @param tenantId
     *            tenant UUID
     * @return number of carts deleted
     */
    private int deleteExpiredCartsForTenant(UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now();
        int totalDeleted = 0;

        // Find expired cart IDs in batches
        @SuppressWarnings("unchecked")
        List<UUID> expiredCartIds = entityManager.createQuery(
                "SELECT c.id FROM Cart c WHERE c.tenant.id = :tenantId AND c.expiresAt < :now ORDER BY c.expiresAt")
                .setParameter("tenantId", tenantId).setParameter("now", now).setMaxResults(batchSize).getResultList();

        if (expiredCartIds.isEmpty()) {
            return 0;
        }

        // Delete cart items first (avoid FK constraint violations)
        int itemsDeleted = entityManager
                .createQuery("DELETE FROM CartItem ci WHERE ci.cart.id IN :cartIds AND ci.tenant.id = :tenantId")
                .setParameter("cartIds", expiredCartIds).setParameter("tenantId", tenantId).executeUpdate();

        // Delete carts
        int cartsDeleted = entityManager
                .createQuery("DELETE FROM Cart c WHERE c.id IN :cartIds AND c.tenant.id = :tenantId")
                .setParameter("cartIds", expiredCartIds).setParameter("tenantId", tenantId).executeUpdate();

        totalDeleted += cartsDeleted;

        LOG.debugf("Deleted expired carts batch - tenantId=%s, cartsDeleted=%d, itemsDeleted=%d", tenantId,
                cartsDeleted, itemsDeleted);

        // If we hit the batch limit, recursively process next batch
        if (expiredCartIds.size() == batchSize) {
            totalDeleted += deleteExpiredCartsForTenant(tenantId);
        }

        return totalDeleted;
    }

    /**
     * Manual trigger for cart expiration (useful for testing or administrative operations).
     *
     * @return number of carts deleted across all tenants
     */
    @Transactional
    public int manualCleanup() {
        LOG.info("Manual cart expiration cleanup triggered");
        Instant startTime = Instant.now();

        List<Tenant> tenants = Tenant.find("status", "active").list();
        int totalDeleted = 0;

        for (Tenant tenant : tenants) {
            try {
                TenantContext.setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));
                totalDeleted += deleteExpiredCartsForTenant(tenant.id);
            } finally {
                TenantContext.clear();
            }
        }

        Duration duration = Duration.between(startTime, Instant.now());
        LOG.infof("Manual cart cleanup completed - deleted=%d, duration=%dms", totalDeleted, duration.toMillis());

        return totalDeleted;
    }
}
