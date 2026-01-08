package villagecompute.storefront.services.jobs;

import java.time.LocalDate;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.services.ReportingJobService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduled jobs for automatic refresh of reporting aggregates.
 *
 * <p>
 * Runs periodic background tasks to keep reporting data fresh according to SLA requirements (<15 min). Each job
 * iterates over all active tenants and enqueues refresh tasks.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T3 - Reporting Projection Service</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.2.9 - Scheduler & Reporting Consumption)</li>
 * <li>SLA: Aggregates must update within 15 minutes</li>
 * </ul>
 */
@ApplicationScoped
public class ReportingScheduledJobs {

    private static final Logger LOG = Logger.getLogger(ReportingScheduledJobs.class);

    @Inject
    ReportingJobService reportingJobService;

    /**
     * Refresh sales aggregates every 15 minutes.
     *
     * <p>
     * Refreshes current day and previous day to capture late-arriving cart data.
     */
    @Scheduled(
            cron = "0 */15 * * * ?",
            identity = "refresh-sales-aggregates")
    public void refreshSalesAggregates() {
        LOG.info("Starting scheduled refresh of sales aggregates");

        try {
            List<Tenant> tenants = Tenant.find("status", "active").list();
            LOG.infof("Refreshing sales aggregates for %d active tenants", tenants.size());

            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);

            for (Tenant tenant : tenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);

                    // Refresh yesterday and today
                    reportingJobService.enqueueRefresh("sales_by_period", yesterday, yesterday);
                    reportingJobService.enqueueRefresh("sales_by_period", today, today);

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to enqueue sales refresh for tenant %s", tenant.id);
                } finally {
                    TenantContext.clear();
                }
            }

            // Process enqueued jobs
            int processed = 0;
            while (reportingJobService.processNextRefreshJob()) {
                processed++;
            }

            LOG.infof("Completed sales aggregate refresh - processed %d jobs", processed);

        } catch (Exception e) {
            LOG.error("Failed to refresh sales aggregates", e);
        }
    }

    /**
     * Refresh consignment payout aggregates every 30 minutes.
     *
     * <p>
     * Refreshes current month to capture new consignment item sales.
     */
    @Scheduled(
            cron = "0 */30 * * * ?",
            identity = "refresh-consignment-payouts")
    public void refreshConsignmentPayoutAggregates() {
        LOG.info("Starting scheduled refresh of consignment payout aggregates");

        try {
            List<Tenant> tenants = Tenant.find("status", "active").list();
            LOG.infof("Refreshing consignment payout aggregates for %d active tenants", tenants.size());

            LocalDate firstOfMonth = LocalDate.now().withDayOfMonth(1);
            LocalDate today = LocalDate.now();

            for (Tenant tenant : tenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);
                    reportingJobService.enqueueRefresh("consignment_payout", firstOfMonth, today);

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to enqueue consignment payout refresh for tenant %s", tenant.id);
                } finally {
                    TenantContext.clear();
                }
            }

            // Process enqueued jobs
            int processed = 0;
            while (reportingJobService.processNextRefreshJob()) {
                processed++;
            }

            LOG.infof("Completed consignment payout aggregate refresh - processed %d jobs", processed);

        } catch (Exception e) {
            LOG.error("Failed to refresh consignment payout aggregates", e);
        }
    }

    /**
     * Refresh inventory aging aggregates every hour.
     *
     * <p>
     * Computes days in stock for all inventory items across all locations.
     */
    @Scheduled(
            cron = "0 0 * * * ?",
            identity = "refresh-inventory-aging")
    public void refreshInventoryAgingAggregates() {
        LOG.info("Starting scheduled refresh of inventory aging aggregates");

        try {
            List<Tenant> tenants = Tenant.find("status", "active").list();
            LOG.infof("Refreshing inventory aging aggregates for %d active tenants", tenants.size());

            for (Tenant tenant : tenants) {
                try {
                    TenantContext.setCurrentTenantId(tenant.id);
                    reportingJobService.enqueueRefresh("inventory_aging", null, null);

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to enqueue inventory aging refresh for tenant %s", tenant.id);
                } finally {
                    TenantContext.clear();
                }
            }

            // Process enqueued jobs
            int processed = 0;
            while (reportingJobService.processNextRefreshJob()) {
                processed++;
            }

            LOG.infof("Completed inventory aging aggregate refresh - processed %d jobs", processed);

        } catch (Exception e) {
            LOG.error("Failed to refresh inventory aging aggregates", e);
        }
    }

    /**
     * Process export job queue every 5 minutes.
     *
     * <p>
     * Drains the export queue to generate and upload reports to R2.
     */
    @Scheduled(
            cron = "0 */5 * * * ?",
            identity = "process-export-queue")
    public void processExportQueue() {
        LOG.info("Starting export queue processing");

        try {
            int processed = 0;
            while (reportingJobService.processNextExportJob()) {
                processed++;
                // Limit batch size to avoid long-running jobs
                if (processed >= 50) {
                    LOG.infof("Reached batch limit of 50 exports, will continue in next cycle");
                    break;
                }
            }

            if (processed > 0) {
                LOG.infof("Completed export queue processing - processed %d jobs", processed);
            }

        } catch (Exception e) {
            LOG.error("Failed to process export queue", e);
        }
    }
}
