package villagecompute.storefront.services.returns;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Lightweight queue capturing return-related notification payloads.
 */
@ApplicationScoped
public class ReturnNotificationTaskQueue {

    private final Queue<ReturnNotificationTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    @Inject
    public ReturnNotificationTaskQueue(MeterRegistry meterRegistry) {
        meterRegistry.gauge("returns.notifications.queue_depth", queueDepth, AtomicInteger::get);
    }

    public UUID enqueue(ReturnNotificationTask task) {
        queue.add(task);
        queueDepth.incrementAndGet();
        return task.getTaskId();
    }

    public ReturnNotificationTask poll() {
        ReturnNotificationTask task = queue.poll();
        if (task != null) {
            queueDepth.decrementAndGet();
        }
        return task;
    }

    public int getQueueDepth() {
        return queueDepth.get();
    }

    public List<ReturnNotificationTask> snapshot() {
        return List.copyOf(queue);
    }

    public void clear() {
        queue.clear();
        queueDepth.set(0);
    }
}
