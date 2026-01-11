package villagecompute.storefront.testsupport;

import jakarta.persistence.EntityManager;

/**
 * Utility to purge tenant-scoped tables between tests. Ensures tables are cleared in a safe order so `DELETE FROM
 * Tenant` will not violate foreign key constraints when H2 is used in PostgreSQL compatibility mode.
 */
public final class TestDataCleaner {

    private TestDataCleaner() {
    }

    public static void clearTenantData(EntityManager entityManager) {
        entityManager.createQuery("DELETE FROM ReportJob").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransferLine").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryTransfer").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryAdjustment").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryAgingAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentPayoutAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM SalesByPeriodAggregate").executeUpdate();
        entityManager.createQuery("DELETE FROM CartItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Cart").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLevel").executeUpdate();
        entityManager.createQuery("DELETE FROM InventoryLocation").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutLineItem").executeUpdate();
        entityManager.createQuery("DELETE FROM PayoutBatch").executeUpdate();
        entityManager.createQuery("DELETE FROM ConsignmentItem").executeUpdate();
        entityManager.createQuery("DELETE FROM Consignor").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductCollection").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductImage").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductCategory").executeUpdate();
        entityManager.createQuery("DELETE FROM ProductVariant").executeUpdate();
        entityManager.createQuery("DELETE FROM Product").executeUpdate();
        entityManager.createQuery("DELETE FROM Collection").executeUpdate();
        entityManager.createQuery("DELETE FROM Category").executeUpdate();
        entityManager.createQuery("DELETE FROM FeatureFlag").executeUpdate();
        entityManager.createQuery("DELETE FROM IdempotencyKey").executeUpdate();
        entityManager.createQuery("DELETE FROM PaymentTender").executeUpdate();
        entityManager.createQuery("DELETE FROM PaymentIntent").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCardTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM GiftCard").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditTransaction").executeUpdate();
        entityManager.createQuery("DELETE FROM StoreCreditAccount").executeUpdate();
        entityManager.createQuery("DELETE FROM CustomDomain").executeUpdate();
        entityManager.createQuery("DELETE FROM User").executeUpdate();
        entityManager.createQuery("DELETE FROM Tenant").executeUpdate();
    }
}
