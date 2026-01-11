package villagecompute.storefront.services.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.InventoryLevel;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.InventoryLevelRepository;
import villagecompute.storefront.notifications.InventoryNotificationService;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.tenant.TenantInfo;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.transaction.Transactional;

/**
 * Scheduled job for low stock alerting.
 *
 * <p>
 * Periodically scans inventory levels across all active tenants and identifies items that have fallen below their
 * configured low stock thresholds. Emits log entries and metrics for monitoring and alerting systems.
 *
 * <p>
 * This is a stub implementation that logs alerts and emits metrics. Future enhancements will add email/webhook
 * notifications via the notification service.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Inventory subsystem with low-stock alert scheduler stub</li>
 * <li>Architecture: Multi-location inventory system (docs/inventory/multi_location.md)</li>
 * <li>Acceptance Criteria: Scheduler stub logs and respects feature flag gating + SLA metrics instrumentation</li>
 * </ul>
 */
@ApplicationScoped
public class LowStockAlertScheduler {

    private static final Logger LOG = Logger.getLogger(LowStockAlertScheduler.class);

    @Inject
    InventoryLevelRepository inventoryLevelRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    InventoryNotificationService inventoryNotificationService;

    @ConfigProperty(
            name = "inventory.low-stock-alerts.enabled",
            defaultValue = "true")
    public boolean lowStockAlertsEnabled;

    @ConfigProperty(
            name = "inventory.low-stock-alerts.cron",
            defaultValue = "0 0 */4 * * ?")
    String alertScheduleCron;

    @ConfigProperty(
            name = "inventory.low-stock-alerts.email.enabled",
            defaultValue = "false")
    public boolean lowStockAlertEmailsEnabled;

    public void setLowStockAlertsEnabled(boolean enabled) {
        this.lowStockAlertsEnabled = enabled;
    }

    public void setLowStockAlertEmailsEnabled(boolean enabled) {
        this.lowStockAlertEmailsEnabled = enabled;
    }

    /**
     * Scan inventory levels and log low stock alerts every 4 hours.
     *
     * <p>
     * Respects feature flag gating via {@code inventory.low-stock-alerts.enabled} property. Emits SLA metrics
     * (duration, items scanned, alerts generated) per tenant for observability.
     */
    @Scheduled(
            cron = "${inventory.low-stock-alerts.cron:0 0 */4 * * ?}",
            identity = "low-stock-alerts")
    @Transactional
    public void scanForLowStockAlerts() {
        if (!lowStockAlertsEnabled) {
            LOG.debug("Low stock alerts disabled via feature flag, skipping scan");
            return;
        }

        LOG.info("Starting low stock alert scan");
        Instant startTime = Instant.now();

        try {
            List<Tenant> tenants = Tenant.find("status", "active").list();
            LOG.infof("Scanning low stock alerts for %d active tenants", tenants.size());

            int totalScanned = 0;
            int totalAlerts = 0;

            for (Tenant tenant : tenants) {
                try {
                    TenantContext
                            .setCurrentTenant(new TenantInfo(tenant.id, tenant.subdomain, tenant.name, tenant.status));

                    Instant tenantStartTime = Instant.now();
                    int tenantAlerts = processLowStockAlertsForTenant(tenant.id);
                    Duration tenantDuration = Duration.between(tenantStartTime, Instant.now());

                    int tenantInventoryCount = inventoryLevelRepository.countForTenant();
                    totalScanned += tenantInventoryCount;
                    totalAlerts += tenantAlerts;

                    // Record SLA metrics per tenant
                    meterRegistry.timer("inventory.low_stock.scan_duration", "tenant_id", tenant.id.toString())
                            .record(tenantDuration);
                    meterRegistry.counter("inventory.low_stock.items_scanned", "tenant_id", tenant.id.toString())
                            .increment(tenantInventoryCount);
                    meterRegistry.counter("inventory.low_stock.alerts_generated", "tenant_id", tenant.id.toString())
                            .increment(tenantAlerts);

                    if (tenantAlerts > 0) {
                        LOG.infof("Low stock alerts generated - tenantId=%s, alertCount=%d, scanned=%d, duration=%dms",
                                tenant.id, tenantAlerts, tenantInventoryCount, tenantDuration.toMillis());
                    }

                } catch (Exception e) {
                    LOG.errorf(e, "Failed to process low stock alerts for tenant %s", tenant.id);
                    meterRegistry.counter("inventory.low_stock.scan_errors", "tenant_id", tenant.id.toString())
                            .increment();
                } finally {
                    TenantContext.clear();
                }
            }

            Duration totalDuration = Duration.between(startTime, Instant.now());
            LOG.infof("Completed low stock alert scan - tenants=%d, scanned=%d, alerts=%d, duration=%dms",
                    tenants.size(), totalScanned, totalAlerts, totalDuration.toMillis());

            // Record global metrics
            meterRegistry.timer("inventory.low_stock.scan_duration_global").record(totalDuration);
            meterRegistry.counter("inventory.low_stock.items_scanned_global").increment(totalScanned);
            meterRegistry.counter("inventory.low_stock.alerts_generated_global").increment(totalAlerts);

        } catch (Exception e) {
            LOG.error("Failed to complete low stock alert scan", e);
            meterRegistry.counter("inventory.low_stock.scan_errors_global").increment();
        }
    }

    /**
     * Process low stock alerts for a single tenant.
     *
     * @param tenantId
     *            tenant UUID
     * @return number of alerts generated
     */
    private int processLowStockAlertsForTenant(UUID tenantId) {
        List<InventoryLevel> lowStockLevels = inventoryLevelRepository.findLowStockItems();
        if (lowStockLevels.isEmpty()) {
            LOG.debugf("No low stock items found - tenantId=%s", tenantId);
            return 0;
        }

        for (InventoryLevel level : lowStockLevels) {
            LOG.warnf(
                    "LOW STOCK ALERT - tenantId=%s, variantId=%s, sku=%s, location=%s, quantity=%d, threshold=%d, available=%d",
                    tenantId, level.variant.id, level.variant.sku, level.location, level.quantity,
                    level.lowStockThreshold, level.getAvailableQuantity());
            if (lowStockAlertEmailsEnabled) {
                inventoryNotificationService.enqueueLowStockAlert(level);
            } else {
                LOG.debugf("Low stock email alerts disabled - tenantId=%s, variantId=%s", tenantId, level.variant.id);
            }
        }

        return lowStockLevels.size();
    }
}
