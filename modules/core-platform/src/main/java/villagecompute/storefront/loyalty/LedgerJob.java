package villagecompute.storefront.loyalty;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled job for cleaning up expired loyalty redemption reservations.
 *
 * <p>
 * Runs every 5 minutes to expire reservations past their expiration date. Iterates over all active tenants and
 * processes expired reservations for each tenant independently. This prevents abandoned cart reservations from holding
 * points indefinitely.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T3: Loyalty ledger with reservation cleanup</li>
 * <li>Architecture: Section 3.19.9 - Loyalty operational tasks</li>
 * <li>Schedule: Configurable via loyalty.reservation.cleanup.cron (defaults to every 5 minutes)</li>
 * </ul>
 */
@ApplicationScoped
public class LedgerJob {

    private static final Logger LOG = Logger.getLogger(LedgerJob.class);

    @Inject
    LoyaltyService loyaltyService;

    /**
     * Clean up expired loyalty redemption reservations for all active tenants.
     *
     * <p>
     * This job runs frequently (default every 5 minutes) to process expired reservations across all tenants. Each
     * tenant's cleanup is processed in a separate transaction to ensure tenant isolation and prevent cross-tenant
     * failures.
     *
     * <p>
     * The schedule is configurable via application.properties using the loyalty.reservation.cleanup.cron property.
     * Defaults to "0 0/5 * * * ?" (every 5 minutes).
     */
    @Scheduled(
            cron = "${loyalty.reservation.cleanup.cron:0 0/5 * * * ?}",
            identity = "cleanup-loyalty-reservations")
    public void cleanupExpiredReservations() {
        LOG.info("Starting loyalty reservation cleanup job");

        OffsetDateTime cutoffDate = OffsetDateTime.now();
        int totalExpired = 0;
        int tenantCount = 0;
        int failureCount = 0;

        try {
            List<Tenant> activeTenants = Tenant.find("status", "active").list();
            LOG.infof("Processing loyalty reservation cleanup for %d active tenants - cutoffDate=%s",
                    activeTenants.size(), cutoffDate);

            for (Tenant tenant : activeTenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);
                    int expiredCount = processCleanupForTenant(tenant, cutoffDate);
                    totalExpired += expiredCount;
                    tenantCount++;

                    if (expiredCount > 0) {
                        LOG.infof("Expired loyalty reservations - tenantId=%s, reservationsExpired=%d", tenant.id,
                                expiredCount);
                    }
                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to clean up loyalty reservations for tenant %s", tenant.id);
                } finally {
                    TenantContext.clear();
                }
            }

            LOG.infof(
                    "Loyalty reservation cleanup job completed - tenantsProcessed=%d, totalReservationsExpired=%d, failures=%d",
                    tenantCount, totalExpired, failureCount);

        } catch (Exception e) {
            LOG.error("Failed to execute loyalty reservation cleanup job", e);
        }
    }

    /**
     * Process reservation cleanup for a single tenant.
     *
     * <p>
     * This method is executed within a tenant context and processes all expired reservations for that tenant. It uses a
     * separate transaction to ensure tenant isolation.
     *
     * @param tenant
     *            tenant to process
     * @param cutoffDate
     *            expiration cutoff timestamp
     * @return number of reservations expired
     */
    @Transactional
    public int processCleanupForTenant(Tenant tenant, OffsetDateTime cutoffDate) {
        // LoyaltyService.expireReservations() already handles tenant-scoped queries via RLS
        // and processes expired reservations in batches of 1000
        return loyaltyService.expireReservations(cutoffDate);
    }
}
