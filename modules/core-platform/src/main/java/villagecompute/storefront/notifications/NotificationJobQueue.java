package villagecompute.storefront.notifications;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Simple in-memory queue capturing notification jobs until the dedicated worker processes them.
 *
 * <p>
 * Mirrors the MVP queue implementation used by other modules (inventory barcode labels, reporting projections) so the
 * background worker can evolve without blocking feature delivery.
 */
@ApplicationScoped
public class NotificationJobQueue {

    private final Queue<NotificationJobPayload> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    @Inject
    public NotificationJobQueue(MeterRegistry meterRegistry) {
        meterRegistry.gauge("notifications.queue.depth", queueDepth, AtomicInteger::get);
    }

    /**
     * Enqueue a new payload for async delivery.
     *
     * @param payload
     *            notification job payload
     * @return job ID
     */
    public UUID enqueue(NotificationJobPayload payload) {
        queue.add(payload);
        queueDepth.incrementAndGet();
        return payload.getJobId();
    }

    /**
     * Remove the next payload from the queue for processing.
     *
     * @return payload or {@code null} when queue is empty
     */
    public NotificationJobPayload poll() {
        NotificationJobPayload payload = queue.poll();
        if (payload != null) {
            queueDepth.decrementAndGet();
        }
        return payload;
    }

    public int getQueueDepth() {
        return queueDepth.get();
    }
}
