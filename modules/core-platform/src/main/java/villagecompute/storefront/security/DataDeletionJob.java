package villagecompute.storefront.security;

import java.util.function.BooleanSupplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.compliance.ComplianceService;

import io.quarkus.scheduler.Scheduled;

/**
 * Background worker that drains the privacy deletion queue (soft-delete + purge).
 */
@ApplicationScoped
public class DataDeletionJob {

    private static final Logger LOG = Logger.getLogger(DataDeletionJob.class);
    private static final int MAX_BATCH = 15;

    @Inject
    ComplianceService complianceService;

    @Scheduled(
            every = "20s",
            identity = "privacy-delete-processor")
    void processDeleteQueue() {
        drainQueue("privacy-delete", complianceService::processNextDeleteJob);
    }

    private void drainQueue(String queueName, BooleanSupplier processor) {
        try {
            int processed = 0;
            while (processor.getAsBoolean()) {
                processed++;
                if (processed >= MAX_BATCH) {
                    LOG.infof("Queue %s processed %d jobs (batch limit reached)", queueName, processed);
                    break;
                }
            }
            if (processed > 0) {
                LOG.infof("Queue %s processed %d jobs", queueName, processed);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to drain %s queue", queueName);
        }
    }
}
