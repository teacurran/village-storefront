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
 * Scheduled job for nightly expiration of loyalty points.
 *
 * <p>
 * Runs daily at 2 AM to expire points past their expiration date. Iterates over all active tenants and processes
 * expired transactions for each tenant independently.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I5.T1 - Loyalty module implementation</li>
 * <li>Architecture: docs/loyalty/program.md (Section: Expiration Handling)</li>
 * <li>Schedule: Configurable via loyalty.expiration.cron (defaults to 2 AM daily)</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyExpirationJob {

    private static final Logger LOG = Logger.getLogger(LoyaltyExpirationJob.class);

    @Inject
    LoyaltyService loyaltyService;

    /**
     * Expire loyalty points for all active tenants.
     *
     * <p>
     * This job runs nightly to process expired points across all tenants. Each tenant's expiration is processed in a
     * separate transaction to ensure tenant isolation and prevent cross-tenant failures.
     *
     * <p>
     * The schedule is configurable via application.properties using the loyalty.expiration.cron property. Defaults to
     * "0 0 2 * * ?" (2 AM daily).
     */
    @Scheduled(
            cron = "${loyalty.expiration.cron:0 0 2 * * ?}",
            identity = "expire-loyalty-points")
    public void expirePoints() {
        LOG.info("Starting loyalty points expiration job");

        OffsetDateTime cutoffDate = OffsetDateTime.now();
        int totalExpired = 0;
        int tenantCount = 0;
        int failureCount = 0;

        try {
            List<Tenant> activeTenants = Tenant.find("status", "active").list();
            LOG.infof("Processing loyalty expiration for %d active tenants - cutoffDate=%s", activeTenants.size(),
                    cutoffDate);

            for (Tenant tenant : activeTenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);
                    int expiredCount = processExpirationForTenant(tenant, cutoffDate);
                    totalExpired += expiredCount;
                    tenantCount++;

                    if (expiredCount > 0) {
                        LOG.infof("Expired loyalty points - tenantId=%s, transactionsExpired=%d", tenant.id,
                                expiredCount);
                    }
                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to expire loyalty points for tenant %s", tenant.id);
                } finally {
                    TenantContext.clear();
                }
            }

            LOG.infof(
                    "Loyalty points expiration job completed - tenantsProcessed=%d, totalTransactionsExpired=%d, failures=%d",
                    tenantCount, totalExpired, failureCount);

        } catch (Exception e) {
            LOG.error("Failed to execute loyalty points expiration job", e);
        }
    }

    /**
     * Process expiration for a single tenant.
     *
     * <p>
     * This method is executed within a tenant context and processes all expired transactions for that tenant. It uses a
     * separate transaction to ensure tenant isolation.
     *
     * @param tenant
     *            tenant to process
     * @param cutoffDate
     *            expiration cutoff timestamp
     * @return number of transactions expired
     */
    @Transactional
    public int processExpirationForTenant(Tenant tenant, OffsetDateTime cutoffDate) {
        // LoyaltyService.expirePoints() already handles tenant-scoped queries via RLS
        // and processes expired transactions in batches of 1000
        return loyaltyService.expirePoints(cutoffDate);
    }
}
