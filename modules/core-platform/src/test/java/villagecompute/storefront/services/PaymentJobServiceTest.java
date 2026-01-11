package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.FeatureFlag;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.jobs.PayoutReconciliationJobHandler;
import villagecompute.storefront.tenant.TenantContext;
import villagecompute.storefront.testsupport.TestDataCleaner;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

@QuarkusTest
class PaymentJobServiceTest {

    @Inject
    PaymentJobService paymentJobService;

    @Inject
    FeatureToggle featureToggle;

    @InjectMock
    PayoutReconciliationJobHandler payoutReconciliationJobHandler;

    private Tenant tenant;
    private Consignor consignor;
    private PayoutBatch payoutBatch;

    @Inject
    EntityManager entityManager;

    @BeforeEach
    @Transactional
    void setUp() {
        TestDataCleaner.clearTenantData(entityManager);

        tenant = new Tenant();
        tenant.subdomain = "payout-test";
        tenant.name = "Payout Test Tenant";
        tenant.status = "active";
        OffsetDateTime now = OffsetDateTime.now();
        tenant.createdAt = now;
        tenant.updatedAt = now;
        tenant.persist();

        consignor = new Consignor();
        consignor.tenant = tenant;
        consignor.name = "Consignor QA";
        consignor.status = "active";
        consignor.createdAt = now;
        consignor.updatedAt = now;
        consignor.persist();

        payoutBatch = new PayoutBatch();
        payoutBatch.tenant = tenant;
        payoutBatch.consignor = consignor;
        payoutBatch.periodStart = LocalDate.now().minusDays(7);
        payoutBatch.periodEnd = LocalDate.now();
        payoutBatch.totalAmount = new BigDecimal("150.00");
        payoutBatch.currency = "USD";
        payoutBatch.status = "completed";
        payoutBatch.paymentReference = "po_test";
        payoutBatch.persist();

        createFeatureFlag(true);
        featureToggle.invalidateAll();
        TenantContext.setCurrentTenantId(tenant.id);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void enqueuePayoutReconciliationWhenEnabled() throws Exception {
        doNothing().when(payoutReconciliationJobHandler).handle(org.mockito.ArgumentMatchers.any());

        Optional<UUID> jobId = paymentJobService.enqueuePayoutReconciliation(payoutBatch, "po_enabled");

        assertTrue(jobId.isPresent(), "Job should be enqueued when feature flag enabled");
        assertTrue(paymentJobService.processNextPayoutReconciliation(), "Processor should handle enqueued job");
        verify(payoutReconciliationJobHandler).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @Transactional
    void enqueueSkippedWhenFeatureDisabled() {
        createFeatureFlag(false);
        featureToggle.invalidateAll();

        Optional<UUID> jobId = paymentJobService.enqueuePayoutReconciliation(payoutBatch, "po_disabled");
        assertTrue(jobId.isEmpty(), "Job should not enqueue when feature disabled");
    }

    void createFeatureFlag(boolean enabled) {
        FeatureFlag.delete("flagKey", "payments.payout.reconciliation.enabled");

        FeatureFlag flag = new FeatureFlag();
        flag.flagKey = "payments.payout.reconciliation.enabled";
        flag.enabled = enabled;
        flag.owner = "payments-team";
        flag.riskLevel = "HIGH";
        flag.reviewCadenceDays = 90;
        flag.description = "Controls payout reconciliation workers";
        flag.rollbackInstructions = "Set enabled=false to pause payout jobs";
        OffsetDateTime now = OffsetDateTime.now();
        flag.createdAt = now;
        flag.updatedAt = now;
        flag.persist();
    }
}
