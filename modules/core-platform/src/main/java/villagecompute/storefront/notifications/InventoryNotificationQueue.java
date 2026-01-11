package villagecompute.storefront.notifications;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Lightweight in-memory queue capturing inventory notifications until async workers deliver them.
 */
@ApplicationScoped
public class InventoryNotificationQueue {

    private static final Logger LOG = Logger.getLogger(InventoryNotificationQueue.class);

    private final Queue<InventoryNotificationPayload> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    @Inject
    public InventoryNotificationQueue(MeterRegistry meterRegistry) {
        meterRegistry.gauge("inventory.notifications.queue_depth", queueDepth, AtomicInteger::get);
    }

    public UUID enqueue(InventoryNotificationPayload payload) {
        queue.add(payload);
        int depth = queueDepth.incrementAndGet();
        LOG.debugf("Inventory notification enqueued - type=%s, queueDepth=%d", payload.getType(), depth);
        return payload.getNotificationId();
    }

    public InventoryNotificationPayload poll() {
        InventoryNotificationPayload payload = queue.poll();
        if (payload != null) {
            queueDepth.decrementAndGet();
        }
        return payload;
    }

    public int getQueueDepth() {
        return queueDepth.get();
    }

    /**
     * Test helper to reset queue state between integration tests.
     */
    public void clear() {
        queue.clear();
        queueDepth.set(0);
    }
}
