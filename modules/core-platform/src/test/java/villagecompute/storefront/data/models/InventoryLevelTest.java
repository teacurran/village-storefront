package villagecompute.storefront.data.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InventoryLevel}.
 *
 * <p>
 * Tests inventory level business logic including available quantity calculation and low stock detection per Task I2.T2
 * acceptance criteria.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Inventory subsystem with low-stock alert scheduler</li>
 * <li>Acceptance Criteria: Stock adjustments transactional with optimistic locking + reason codes</li>
 * </ul>
 */
class InventoryLevelTest {

    @Test
    void getAvailableQuantity_shouldReturnTotalMinusReserved() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 100;
        level.reserved = 30;

        // Act
        int available = level.getAvailableQuantity();

        // Assert
        assertEquals(70, available, "Available should be 100 - 30 = 70");
    }

    @Test
    void getAvailableQuantity_shouldReturnZeroWhenReservedExceedsQuantity() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 50;
        level.reserved = 75;

        // Act
        int available = level.getAvailableQuantity();

        // Assert
        assertEquals(0, available, "Available should not be negative");
    }

    @Test
    void getAvailableQuantity_shouldHandleZeroValues() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 0;
        level.reserved = 0;

        // Act
        int available = level.getAvailableQuantity();

        // Assert
        assertEquals(0, available);
    }

    @Test
    void isLowStock_shouldReturnTrueWhenBelowThreshold() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 5;
        level.lowStockThreshold = 10;

        // Act & Assert
        assertTrue(level.isLowStock(), "Should be low stock when quantity (5) <= threshold (10)");
    }

    @Test
    void isLowStock_shouldReturnTrueWhenAtThreshold() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 10;
        level.lowStockThreshold = 10;

        // Act & Assert
        assertTrue(level.isLowStock(), "Should be low stock when quantity equals threshold");
    }

    @Test
    void isLowStock_shouldReturnFalseWhenAboveThreshold() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 15;
        level.lowStockThreshold = 10;

        // Act & Assert
        assertFalse(level.isLowStock(), "Should not be low stock when quantity (15) > threshold (10)");
    }

    @Test
    void isLowStock_shouldReturnFalseWhenThresholdNotConfigured() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 5;
        level.lowStockThreshold = 0; // Not configured

        // Act & Assert
        assertFalse(level.isLowStock(), "Should not trigger alert when threshold is 0");
    }

    @Test
    void isLowStock_shouldReturnFalseWhenThresholdIsNull() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = 5;
        level.lowStockThreshold = null;

        // Act & Assert
        assertFalse(level.isLowStock(), "Should not trigger alert when threshold is null");
    }

    @Test
    void isLowStock_shouldUseAvailableQuantity() {
        InventoryLevel level = new InventoryLevel();
        level.quantity = 20;
        level.reserved = 15;
        level.lowStockThreshold = 10;

        assertTrue(level.isLowStock(), "Reserved inventory should reduce available quantity used for alerting");
    }

    @Test
    void isLowStock_shouldHandleNegativeQuantity() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.quantity = -10; // Negative stock (allowed per requirements)
        level.lowStockThreshold = 5;

        // Act & Assert
        assertTrue(level.isLowStock(), "Negative quantity should trigger low stock alert");
    }

    @Test
    void safetyStock_shouldDefaultToZero() {
        // Arrange
        InventoryLevel level = new InventoryLevel();

        // Assert
        assertEquals(0, level.safetyStock, "Safety stock should default to 0");
    }

    @Test
    void lowStockThreshold_shouldDefaultToZero() {
        // Arrange
        InventoryLevel level = new InventoryLevel();

        // Assert
        assertEquals(0, level.lowStockThreshold, "Low stock threshold should default to 0");
    }

    @Test
    void safetyStock_shouldBeConfigurable() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.safetyStock = 25;

        // Assert
        assertEquals(25, level.safetyStock);
    }

    @Test
    void lowStockThreshold_shouldBeConfigurable() {
        // Arrange
        InventoryLevel level = new InventoryLevel();
        level.lowStockThreshold = 15;

        // Assert
        assertEquals(15, level.lowStockThreshold);
    }
}
