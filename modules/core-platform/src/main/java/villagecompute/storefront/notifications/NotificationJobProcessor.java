package villagecompute.storefront.notifications;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;

/**
 * Background worker responsible for draining queued notification jobs.
 *
 * <p>
 * Uses Quarkus Scheduler to poll the in-memory queue on a fixed interval while exposing {@link #processAllPending()}
 * for deterministic execution in tests.
 */
@ApplicationScoped
public class NotificationJobProcessor {

    private static final Logger LOG = Logger.getLogger(NotificationJobProcessor.class);

    @Inject
    NotificationJobQueue notificationJobQueue;

    @Inject
    NotificationService notificationService;

    /**
     * Scheduled dispatcher that drains the queue. Interval defaults to 5 seconds but can be customized via
     * {@code notifications.queue.dispatch-interval}.
     */
    @Scheduled(
            every = "{notifications.queue.dispatch-interval:5s}",
            identity = "notification-job-dispatcher")
    void dispatchScheduledJobs() {
        int processed = processAllPending();
        if (processed > 0) {
            LOG.debugf("Notification dispatcher sent %d emails", processed);
        }
    }

    /**
     * Drain the queue synchronously. Used by unit tests and manual triggers (e.g., maintenance endpoints).
     *
     * @return number of jobs processed
     */
    public int processAllPending() {
        int processed = 0;
        NotificationJobPayload payload;
        while ((payload = notificationJobQueue.poll()) != null) {
            try {
                notificationService.processJob(payload);
                processed++;
            } catch (Exception e) {
                LOG.errorf(e, "Failed to process notification job - jobId=%s, tenantId=%s", payload.getJobId(),
                        payload.getTenantId());
            }
        }
        return processed;
    }
}
