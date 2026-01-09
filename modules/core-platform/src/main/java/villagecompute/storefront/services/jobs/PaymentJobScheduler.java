package villagecompute.storefront.services.jobs;

import java.util.function.BooleanSupplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.services.PaymentJobService;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduler that drains payment-related background queues (currently payout reconciliation). Keeps work flowing without
 * blocking webhook threads.
 */
@ApplicationScoped
public class PaymentJobScheduler {

    private static final Logger LOGGER = Logger.getLogger(PaymentJobScheduler.class);
    private static final int MAX_BATCH_SIZE = 10;

    @Inject
    PaymentJobService paymentJobService;

    @Scheduled(
            every = "10s",
            identity = "payout-reconciliation-processor")
    void processPayoutReconciliationQueue() {
        drainQueue("payments.payout.reconciliation", paymentJobService::processNextPayoutReconciliation);
    }

    private void drainQueue(String queueName, BooleanSupplier processor) {
        try {
            int processed = 0;
            while (processor.getAsBoolean()) {
                processed++;
                if (processed >= MAX_BATCH_SIZE) {
                    LOGGER.debugf("Queue %s processed %d jobs (batch limit reached)", queueName, processed);
                    break;
                }
            }

            if (processed > 0) {
                LOGGER.infof("Queue %s processed %d jobs", queueName, processed);
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Failed to drain queue %s", queueName);
        }
    }
}
