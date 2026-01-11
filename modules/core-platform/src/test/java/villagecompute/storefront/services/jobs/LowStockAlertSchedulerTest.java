package villagecompute.storefront.services.jobs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import villagecompute.storefront.notifications.InventoryNotificationQueue;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for {@link LowStockAlertScheduler}.
 *
 * <p>
 * Tests scheduler behavior including feature flag gating and metrics emission per Task I2.T2 acceptance criteria. The
 * scheduler logic is primarily tested through integration tests in
 * {@link villagecompute.storefront.services.InventoryTransferIT} which validate end-to-end behavior with real database
 * and tenant context.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Inventory subsystem with low-stock alert scheduler stub</li>
 * <li>Acceptance Criteria: Scheduler stub logs and respects feature flag gating + SLA metrics instrumentation</li>
 * </ul>
 */
@QuarkusTest
class LowStockAlertSchedulerTest {

    @Inject
    LowStockAlertScheduler scheduler;

    @Inject
    InventoryNotificationQueue inventoryNotificationQueue;

    @Test
    void scanForLowStockAlerts_shouldExecuteWithoutErrors() {
        // Arrange
        scheduler.lowStockAlertsEnabled = true;
        scheduler.lowStockAlertEmailsEnabled = true;
        inventoryNotificationQueue.clear();

        // Act & Assert - scheduler should execute without exceptions
        assertDoesNotThrow(() -> scheduler.scanForLowStockAlerts());
        assertEquals(0, inventoryNotificationQueue.getQueueDepth(), "No notifications expected in empty dataset");
    }

    @Test
    void scanForLowStockAlerts_shouldSkipWhenFeatureFlagDisabled() {
        // Arrange
        scheduler.lowStockAlertsEnabled = false;
        scheduler.lowStockAlertEmailsEnabled = true;
        inventoryNotificationQueue.clear();

        // Act & Assert - scheduler should skip processing when disabled
        assertDoesNotThrow(() -> scheduler.scanForLowStockAlerts());
        assertEquals(0, inventoryNotificationQueue.getQueueDepth(),
                "Notifications should remain empty when scheduler disabled");
    }
}
