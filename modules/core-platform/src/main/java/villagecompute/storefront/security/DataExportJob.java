package villagecompute.storefront.security;

import java.util.function.BooleanSupplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.compliance.ComplianceService;

import io.quarkus.scheduler.Scheduled;

/**
 * Background worker that processes privacy export jobs so self-service downloads complete automatically.
 */
@ApplicationScoped
public class DataExportJob {

    private static final Logger LOG = Logger.getLogger(DataExportJob.class);
    private static final int MAX_BATCH = 10;

    @Inject
    ComplianceService complianceService;

    @Scheduled(
            every = "15s",
            identity = "privacy-export-processor")
    void processExportQueue() {
        drainQueue("privacy-export", complianceService::processNextExportJob);
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
