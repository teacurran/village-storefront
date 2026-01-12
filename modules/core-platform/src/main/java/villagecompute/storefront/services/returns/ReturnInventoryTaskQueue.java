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
 * In-memory queue capturing inventory adjustment tasks created by the return workflow.
 */
@ApplicationScoped
public class ReturnInventoryTaskQueue {

    private final Queue<ReturnInventoryTask> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    @Inject
    public ReturnInventoryTaskQueue(MeterRegistry meterRegistry) {
        meterRegistry.gauge("returns.inventory.queue_depth", queueDepth, AtomicInteger::get);
    }

    public UUID enqueue(ReturnInventoryTask task) {
        queue.add(task);
        queueDepth.incrementAndGet();
        return task.getTaskId();
    }

    public ReturnInventoryTask poll() {
        ReturnInventoryTask task = queue.poll();
        if (task != null) {
            queueDepth.decrementAndGet();
        }
        return task;
    }

    public int getQueueDepth() {
        return queueDepth.get();
    }

    public List<ReturnInventoryTask> snapshot() {
        return List.copyOf(queue);
    }

    public void clear() {
        queue.clear();
        queueDepth.set(0);
    }
}
