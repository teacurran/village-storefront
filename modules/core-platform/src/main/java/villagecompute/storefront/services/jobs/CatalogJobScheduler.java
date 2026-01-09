package villagecompute.storefront.services.jobs;

import java.util.function.BooleanSupplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import villagecompute.storefront.services.CatalogJobService;

import io.quarkus.scheduler.Scheduled;

/**
 * Scheduler that drains catalog import/export queues at a steady cadence.
 *
 * <p>
 * Workers leverage DelayedJob queues to execute catalog CSV imports/exports asynchronously using DEFAULT priority as
 * defined in the Standard Kit. The scheduler ensures the queues are processed without blocking HTTP threads.
 * </p>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T5 - Catalog Import/Export Foundation</li>
 * <li>Operational Architecture §3.6 - Background Processing</li>
 * </ul>
 */
@ApplicationScoped
public class CatalogJobScheduler {

    private static final Logger LOG = Logger.getLogger(CatalogJobScheduler.class);
    private static final int MAX_BATCH_PER_CYCLE = 25;

    @Inject
    CatalogJobService catalogJobService;

    /**
     * Drain catalog import queue every 15 seconds.
     */
    @Scheduled(
            every = "15s",
            identity = "catalog-import-processor")
    void processImportQueue() {
        drainQueue("catalog-import", catalogJobService::processNextImportJob);
    }

    /**
     * Drain catalog export queue every 30 seconds.
     */
    @Scheduled(
            every = "30s",
            identity = "catalog-export-processor")
    void processExportQueue() {
        drainQueue("catalog-export", catalogJobService::processNextExportJob);
    }

    private void drainQueue(String queueName, BooleanSupplier processor) {
        try {
            int processed = 0;
            while (processor.getAsBoolean()) {
                processed++;
                if (processed >= MAX_BATCH_PER_CYCLE) {
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
