package villagecompute.storefront.services.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Unit tests for {@link InventoryMetrics}.
 *
 * <p>
 * Verifies that inventory metrics are properly registered and incremented when operations are recorded.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Observability instrumentation</li>
 * <li>Acceptance Criteria: Tests ensure metrics registered</li>
 * </ul>
 */
@QuarkusTest
class InventoryMetricsTest {

    @Inject
    InventoryMetrics inventoryMetrics;

    @Inject
    MeterRegistry meterRegistry;

    private final UUID testTenantId = UUID.randomUUID();
    private final String testLocation = "warehouse-01";

    @Test
    void testLevelUpdatedMetricRegistered() {
        // When
        inventoryMetrics.recordLevelUpdated(testTenantId, testLocation);

        // Then
        Counter counter = meterRegistry.find("inventory.level.updated").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).counter();

        assertNotNull(counter, "Level updated counter should be registered");
        assertEquals(1.0, counter.count(), "Level updated counter should be incremented by 1");
    }

    @Test
    void testAdjustmentMetricRegistered() {
        // When
        inventoryMetrics.recordAdjustment(testTenantId, testLocation, "damaged", -5);

        // Then
        Counter counter = meterRegistry.find("inventory.adjustment").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).tag("reason", "damaged").counter();
        Counter quantityCounter = meterRegistry.find("inventory.adjustment.quantity")
                .tag("tenant_id", testTenantId.toString()).tag("location", testLocation).tag("direction", "decrease")
                .counter();

        assertNotNull(counter, "Adjustment counter should be registered");
        assertEquals(1.0, counter.count(), "Adjustment counter should be incremented by 1");

        assertNotNull(quantityCounter, "Adjustment quantity counter should be registered");
        assertEquals(5.0, quantityCounter.count(), "Adjustment quantity counter should record absolute quantity");
    }

    @Test
    void testAdjustmentDurationMetricRegistered() {
        // When
        inventoryMetrics.recordAdjustmentDuration(testTenantId, 150L);

        // Then
        Timer timer = meterRegistry.find("inventory.adjustment.duration").tag("tenant_id", testTenantId.toString())
                .timer();

        assertNotNull(timer, "Adjustment duration timer should be registered");
        assertEquals(1L, timer.count(), "Adjustment duration timer should record 1 execution");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 150.0,
                "Adjustment duration should record >= 150ms");
    }

    @Test
    void testReservedMetricRegistered() {
        // When
        inventoryMetrics.recordReserved(testTenantId, testLocation, 3);

        // Then
        Counter counter = meterRegistry.find("inventory.reserved").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).counter();

        assertNotNull(counter, "Reserved counter should be registered");
        assertEquals(3.0, counter.count(), "Reserved counter should be incremented by reserved quantity");
    }

    @Test
    void testCommittedMetricRegistered() {
        // When
        inventoryMetrics.recordCommitted(testTenantId, testLocation, 2);

        // Then
        Counter counter = meterRegistry.find("inventory.committed").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).counter();

        assertNotNull(counter, "Committed counter should be registered");
        assertEquals(2.0, counter.count(), "Committed counter should be incremented by committed quantity");
    }

    @Test
    void testReleasedMetricRegistered() {
        // When
        inventoryMetrics.recordReleased(testTenantId, testLocation, 1);

        // Then
        Counter counter = meterRegistry.find("inventory.released").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).counter();

        assertNotNull(counter, "Released counter should be registered");
        assertEquals(1.0, counter.count(), "Released counter should be incremented by released quantity");
    }

    @Test
    void testReservationFailedMetricRegistered() {
        // When
        inventoryMetrics.recordReservationFailed(testTenantId, "insufficient_stock");

        // Then
        Counter counter = meterRegistry.find("inventory.reservation.failed").tag("tenant_id", testTenantId.toString())
                .tag("reason", "insufficient_stock").counter();

        assertNotNull(counter, "Reservation failed counter should be registered");
        assertEquals(1.0, counter.count(), "Reservation failed counter should be incremented by 1");
    }

    @Test
    void testTransferCreatedMetricRegistered() {
        // When
        inventoryMetrics.recordTransferCreated(testTenantId, "warehouse-01", "retail-store-01");

        // Then
        Counter counter = meterRegistry.find("inventory.transfer.created").tag("tenant_id", testTenantId.toString())
                .tag("source", "warehouse-01").tag("destination", "retail-store-01").counter();

        assertNotNull(counter, "Transfer created counter should be registered");
        assertEquals(1.0, counter.count(), "Transfer created counter should be incremented by 1");
    }

    @Test
    void testTransferReceivedMetricRegistered() {
        // When
        inventoryMetrics.recordTransferReceived(testTenantId, "retail-store-01");

        // Then
        Counter counter = meterRegistry.find("inventory.transfer.received").tag("tenant_id", testTenantId.toString())
                .tag("destination", "retail-store-01").counter();

        assertNotNull(counter, "Transfer received counter should be registered");
        assertEquals(1.0, counter.count(), "Transfer received counter should be incremented by 1");
    }

    @Test
    void testTransferDurationMetricRegistered() {
        // When
        inventoryMetrics.recordTransferDuration(testTenantId, 2500L);

        // Then
        Timer timer = meterRegistry.find("inventory.transfer.duration").tag("tenant_id", testTenantId.toString())
                .timer();

        assertNotNull(timer, "Transfer duration timer should be registered");
        assertEquals(1L, timer.count(), "Transfer duration timer should record 1 execution");
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 2500.0,
                "Transfer duration should record >= 2500ms");
    }

    @Test
    void testTransferCancelledMetricRegistered() {
        // When
        inventoryMetrics.recordTransferCancelled(testTenantId, "lost_in_transit");

        // Then
        Counter counter = meterRegistry.find("inventory.transfer.cancelled").tag("tenant_id", testTenantId.toString())
                .tag("reason", "lost_in_transit").counter();

        assertNotNull(counter, "Transfer cancelled counter should be registered");
        assertEquals(1.0, counter.count(), "Transfer cancelled counter should be incremented by 1");
    }

    @Test
    void testStockCheckMetricRegistered() {
        // When
        inventoryMetrics.recordStockCheck(testTenantId, true);
        inventoryMetrics.recordStockCheck(testTenantId, false);

        // Then
        Counter availableCounter = meterRegistry.find("inventory.stock.check").tag("tenant_id", testTenantId.toString())
                .tag("result", "available").counter();
        Counter unavailableCounter = meterRegistry.find("inventory.stock.check")
                .tag("tenant_id", testTenantId.toString()).tag("result", "unavailable").counter();

        assertNotNull(availableCounter, "Stock check available counter should be registered");
        assertEquals(1.0, availableCounter.count(), "Stock check available counter should be incremented by 1");

        assertNotNull(unavailableCounter, "Stock check unavailable counter should be registered");
        assertEquals(1.0, unavailableCounter.count(), "Stock check unavailable counter should be incremented by 1");
    }

    @Test
    void testOutOfStockMetricRegistered() {
        // When
        inventoryMetrics.recordOutOfStock(testTenantId, testLocation);

        // Then
        Counter counter = meterRegistry.find("inventory.stock.out_of_stock").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).counter();

        assertNotNull(counter, "Out of stock counter should be registered");
        assertEquals(1.0, counter.count(), "Out of stock counter should be incremented by 1");
    }

    @Test
    void testLowStockMetricRegistered() {
        // When
        inventoryMetrics.recordLowStock(testTenantId, testLocation);

        // Then
        Counter counter = meterRegistry.find("inventory.stock.low_stock").tag("tenant_id", testTenantId.toString())
                .tag("location", testLocation).counter();

        assertNotNull(counter, "Low stock counter should be registered");
        assertEquals(1.0, counter.count(), "Low stock counter should be incremented by 1");
    }

    @Test
    void testMultipleLocationsMetricsIsolation() {
        String location1 = "warehouse-01";
        String location2 = "retail-store-01";

        // When
        inventoryMetrics.recordLevelUpdated(testTenantId, location1);
        inventoryMetrics.recordLevelUpdated(testTenantId, location1);
        inventoryMetrics.recordLevelUpdated(testTenantId, location2);

        // Then
        Counter location1Counter = meterRegistry.find("inventory.level.updated")
                .tag("tenant_id", testTenantId.toString()).tag("location", location1).counter();
        Counter location2Counter = meterRegistry.find("inventory.level.updated")
                .tag("tenant_id", testTenantId.toString()).tag("location", location2).counter();

        assertNotNull(location1Counter, "Location 1 counter should be registered");
        assertNotNull(location2Counter, "Location 2 counter should be registered");

        assertEquals(2.0, location1Counter.count(), "Location 1 should have 2 level updates");
        assertEquals(1.0, location2Counter.count(), "Location 2 should have 1 level update");
    }

    @Test
    void testMultipleTenantMetricsIsolation() {
        UUID tenant1 = UUID.randomUUID();
        UUID tenant2 = UUID.randomUUID();

        // When
        inventoryMetrics.recordReserved(tenant1, testLocation, 1);
        inventoryMetrics.recordReserved(tenant1, testLocation, 1);
        inventoryMetrics.recordReserved(tenant1, testLocation, 1);
        inventoryMetrics.recordReserved(tenant2, testLocation, 1);

        // Then
        Counter tenant1Counter = meterRegistry.find("inventory.reserved").tag("tenant_id", tenant1.toString())
                .tag("location", testLocation).counter();
        Counter tenant2Counter = meterRegistry.find("inventory.reserved").tag("tenant_id", tenant2.toString())
                .tag("location", testLocation).counter();

        assertNotNull(tenant1Counter, "Tenant 1 counter should be registered");
        assertNotNull(tenant2Counter, "Tenant 2 counter should be registered");

        assertEquals(3.0, tenant1Counter.count(), "Tenant 1 should have 3 reservations");
        assertEquals(1.0, tenant2Counter.count(), "Tenant 2 should have 1 reservation");
    }
}
