package villagecompute.storefront.jobs;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.config.ConsignmentPayoutConfig;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.ConsignmentService;
import villagecompute.storefront.services.PayoutLedgerService;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled jobs for automated consignment payout processing.
 *
 * <p>
 * Provides two main automated workflows:
 * <ul>
 * <li>Batch Creation: Monthly aggregation of consignment sales into payout batches</li>
 * <li>Balance Settlement: Periodic movement of pending balances to available after hold period</li>
 * </ul>
 *
 * <p>
 * Both jobs iterate over all active tenants with proper context isolation and error handling.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Automated consignment payout implementation</li>
 * <li>ADR-004: Consignment payout state machine and lifecycle</li>
 * <li>docs/consignment/domain.md: Payout automation specifications</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentPayoutAutomationJob {

    private static final Logger LOG = Logger.getLogger(ConsignmentPayoutAutomationJob.class);

    @Inject
    ConsignmentService consignmentService;

    @Inject
    PayoutLedgerService payoutLedgerService;

    @Inject
    ConsignmentPayoutConfig payoutConfig;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Automated monthly payout batch creation job.
     *
     * <p>
     * Creates payout batches for all consignors with sales in the previous month. Runs on the 1st of each month at
     * midnight (configurable via {@code consignment.payout.batch-creation.cron}).
     *
     * <p>
     * Batch creation workflow:
     * <ol>
     * <li>Calculate previous month's date range</li>
     * <li>Iterate all active tenants</li>
     * <li>For each tenant, create batches for consignors with sales</li>
     * <li>Apply auto-approval logic based on configured thresholds</li>
     * </ol>
     *
     * <p>
     * Feature flag: {@code consignment.payout.automation.enabled} (default: false, enabled in production)
     */
    @Scheduled(
            cron = "${consignment.payout.batch-creation.cron:0 0 1 * * ?}",
            identity = "create-monthly-payout-batches")
    public void createMonthlyPayoutBatches() {
        if (!payoutConfig.automation().enabled()) {
            LOG.debug("Payout automation disabled, skipping batch creation job");
            return;
        }
        LOG.info("Starting monthly payout batch creation job");

        LocalDate now = LocalDate.now();
        LocalDate previousMonth = now.minusMonths(1);
        LocalDate periodStart = previousMonth.withDayOfMonth(1);
        LocalDate periodEnd = previousMonth.withDayOfMonth(previousMonth.lengthOfMonth());

        int totalBatches = 0;
        int tenantCount = 0;
        int failureCount = 0;

        try {
            List<Tenant> activeTenants = Tenant.find("status", "active").list();
            LOG.infof("Creating payout batches for %d active tenants - period=%s to %s", activeTenants.size(),
                    periodStart, periodEnd);

            for (Tenant tenant : activeTenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);
                    int batchCount = createBatchesForTenant(tenant, periodStart, periodEnd);
                    totalBatches += batchCount;
                    tenantCount++;

                    if (batchCount > 0) {
                        LOG.infof("Created payout batches - tenantId=%s, batchCount=%d, period=%s to %s", tenant.id,
                                batchCount, periodStart, periodEnd);
                    }
                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to create payout batches for tenant %s", tenant.id);
                    meterRegistry.counter("consignment.payout.batch.creation.failed", "tenant", tenant.id.toString(),
                            "reason", "exception").increment();
                } finally {
                    TenantContext.clear();
                }
            }

            LOG.infof("Monthly payout batch creation completed - tenantsProcessed=%d, totalBatches=%d, failures=%d",
                    tenantCount, totalBatches, failureCount);
            meterRegistry.counter("consignment.payout.batch.creation.job.completed").increment();

        } catch (Exception e) {
            LOG.error("Failed to execute monthly payout batch creation job", e);
            meterRegistry.counter("consignment.payout.batch.creation.job.failed").increment();
        }
    }

    /**
     * Automated balance settlement job.
     *
     * <p>
     * Moves pending balances to available balances after the configured hold period. Runs every 6 hours by default
     * (configurable via {@code consignment.payout.settlement.cron}).
     *
     * <p>
     * Settlement workflow:
     * <ol>
     * <li>Calculate hold period cutoff date (now - hold period days)</li>
     * <li>Iterate all active tenants</li>
     * <li>For each tenant, query pending ledger entries older than cutoff</li>
     * <li>Move eligible pending balances to available via ledger service</li>
     * </ol>
     *
     * <p>
     * Feature flag: {@code consignment.payout.settlement.enabled} (default: true)
     */
    @Scheduled(
            cron = "${consignment.payout.settlement.cron:0 0 */6 * * ?}",
            identity = "settle-pending-balances")
    public void settlePendingBalances() {
        if (!payoutConfig.settlement().enabled()) {
            LOG.debug("Settlement disabled, skipping pending balance settlement job");
            return;
        }
        LOG.info("Starting pending balance settlement job");

        int holdPeriodDays = payoutConfig.settlement().holdPeriodDays();
        OffsetDateTime cutoffDate = OffsetDateTime.now().minusDays(holdPeriodDays);

        int totalSettlements = 0;
        int tenantCount = 0;
        int failureCount = 0;

        try {
            List<Tenant> activeTenants = Tenant.find("status", "active").list();
            LOG.infof("Processing balance settlement for %d active tenants - holdPeriodDays=%d, cutoffDate=%s",
                    activeTenants.size(), holdPeriodDays, cutoffDate);

            for (Tenant tenant : activeTenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);
                    int settlementCount = processSettlementForTenant(tenant, cutoffDate);
                    totalSettlements += settlementCount;
                    tenantCount++;

                    if (settlementCount > 0) {
                        LOG.infof("Settled pending balances - tenantId=%s, settlementCount=%d", tenant.id,
                                settlementCount);
                    }
                } catch (Exception e) {
                    failureCount++;
                    LOG.errorf(e, "Failed to settle pending balances for tenant %s", tenant.id);
                    meterRegistry.counter("consignment.payout.settlement.failed", "tenant", tenant.id.toString(),
                            "reason", "exception").increment();
                } finally {
                    TenantContext.clear();
                }
            }

            LOG.infof("Balance settlement job completed - tenantsProcessed=%d, totalSettlements=%d, failures=%d",
                    tenantCount, totalSettlements, failureCount);
            meterRegistry.counter("consignment.payout.settlement.job.completed").increment();

        } catch (Exception e) {
            LOG.error("Failed to execute balance settlement job", e);
            meterRegistry.counter("consignment.payout.settlement.job.failed").increment();
        }
    }

    /**
     * Create payout batches for a single tenant.
     *
     * @param tenant
     *            tenant to process
     * @param periodStart
     *            period start date
     * @param periodEnd
     *            period end date
     * @return number of batches created
     */
    @Transactional
    public int createBatchesForTenant(Tenant tenant, LocalDate periodStart, LocalDate periodEnd) {
        // Delegate to ConsignmentService which will create batches and apply auto-approval logic
        return consignmentService.createPayoutBatchesForPeriod(periodStart, periodEnd);
    }

    /**
     * Process settlement for a single tenant.
     *
     * @param tenant
     *            tenant to process
     * @param cutoffDate
     *            settlement cutoff timestamp
     * @return number of settlements processed
     */
    @Transactional
    public int processSettlementForTenant(Tenant tenant, OffsetDateTime cutoffDate) {
        // Delegate to PayoutLedgerService which will settle pending to available
        return payoutLedgerService.settlePendingSales(cutoffDate);
    }
}
