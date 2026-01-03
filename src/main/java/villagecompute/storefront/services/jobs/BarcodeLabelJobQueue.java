package villagecompute.storefront.services.jobs;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Minimal in-memory queue that captures barcode label job payloads until the real async workers are implemented.
 *
 * <p>
 * The queue maintains a Micrometer gauge so platform operations can observe pending label workload.
 */
@ApplicationScoped
public class BarcodeLabelJobQueue {

    private final Queue<BarcodeLabelJobPayload> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    public BarcodeLabelJobQueue(MeterRegistry meterRegistry) {
        meterRegistry.gauge("inventory.barcode.queue.depth", queueDepth, AtomicInteger::get);
    }

    /**
     * Enqueue the payload and return its job ID.
     *
     * @param payload
     *            job payload
     * @return job ID
     */
    public UUID enqueue(BarcodeLabelJobPayload payload) {
        queue.add(payload);
        queueDepth.incrementAndGet();
        return payload.getJobId();
    }

    /**
     * Remove the next payload from the queue (used by future async worker tests).
     *
     * @return payload or null if queue empty
     */
    public BarcodeLabelJobPayload poll() {
        BarcodeLabelJobPayload payload = queue.poll();
        if (payload != null) {
            queueDepth.decrementAndGet();
        }
        return payload;
    }

    /**
     * @return current queue depth (used for logging/tests)
     */
    public int getQueueDepth() {
        return queueDepth.get();
    }
}
