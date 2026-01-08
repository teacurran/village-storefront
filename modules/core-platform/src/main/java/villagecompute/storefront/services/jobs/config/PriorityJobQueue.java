package villagecompute.storefront.services.jobs.config;

import java.util.EnumMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Priority-aware job queue with Prometheus metrics instrumentation.
 *
 * <p>
 * Maintains separate queues per priority level with depth gauges, enqueue counters, and age tracking. Supports bounded
 * queues with overflow handling.
 *
 * <p>
 * Referenced in Operational Architecture §3.6 (Background Processing).
 *
 * @param <T>
 *            the job payload type (typically a *JobPayload class)
 */
public final class PriorityJobQueue<T> {
    private static final Logger LOG = Logger.getLogger(PriorityJobQueue.class);

    private final String queueName;
    private final MeterRegistry meterRegistry;
    private final JobConfig config;
    private final Map<JobPriority, Queue<JobExecution<T>>> queues;
    private final Map<JobPriority, AtomicInteger> depthGauges;

    /**
     * Creates a new priority job queue.
     *
     * @param queueName
     *            the queue name for metrics (e.g., "reporting.refresh", "notifications")
     * @param meterRegistry
     *            the Micrometer registry for Prometheus metrics
     * @param config
     *            the job configuration defining queue capacities
     */
    public PriorityJobQueue(String queueName, MeterRegistry meterRegistry, JobConfig config) {
        this.queueName = queueName;
        this.meterRegistry = meterRegistry;
        this.config = config;
        this.queues = new EnumMap<>(JobPriority.class);
        this.depthGauges = new EnumMap<>(JobPriority.class);

        // Initialize queues and gauges for each priority
        for (JobPriority priority : JobPriority.values()) {
            Queue<JobExecution<T>> queue = new ConcurrentLinkedQueue<>();
            queues.put(priority, queue);

            AtomicInteger depthGauge = new AtomicInteger(0);
            depthGauges.put(priority, depthGauge);

            // Register queue depth gauge with priority tag
            meterRegistry.gauge(queueName + ".queue.depth",
                    io.micrometer.core.instrument.Tags.of("priority", priority.toMetricTag()), depthGauge,
                    AtomicInteger::get);
        }
    }

    /**
     * Enqueues a job with the specified priority.
     *
     * @param payload
     *            the job payload
     * @param priority
     *            the job priority level
     * @return true if enqueued successfully, false if queue is at capacity
     */
    public boolean enqueue(T payload, JobPriority priority) {
        JobExecution<T> execution = JobExecution.create(payload, priority);
        return enqueueExecution(execution);
    }

    /**
     * Enqueues a job execution (used for retries).
     *
     * @param execution
     *            the job execution wrapper
     * @return true if enqueued successfully, false if queue is at capacity
     */
    public boolean enqueueExecution(JobExecution<T> execution) {
        JobPriority priority = execution.getPriority();
        Queue<JobExecution<T>> queue = queues.get(priority);
        AtomicInteger depthGauge = depthGauges.get(priority);

        // Check capacity limit
        int capacity = config.getQueueCapacity(priority);
        if (depthGauge.get() >= capacity) {
            LOG.warnf("Queue %s at capacity for priority %s (limit: %d)", queueName, priority, capacity);
            meterRegistry.counter(queueName + ".queue.overflow", "priority", priority.toMetricTag()).increment();
            return false;
        }

        // Add to queue and update metrics
        queue.offer(execution);
        depthGauge.incrementAndGet();

        meterRegistry.counter(queueName + ".job.enqueued", "priority", priority.toMetricTag()).increment();

        // Track job age at enqueue time
        meterRegistry.timer(queueName + ".job.age", "priority", priority.toMetricTag()).record(execution.getAgeMs(),
                java.util.concurrent.TimeUnit.MILLISECONDS);

        return true;
    }

    /**
     * Polls the next job from the highest priority non-empty queue.
     *
     * @return the next job execution, or null if all queues are empty
     */
    public JobExecution<T> poll() {
        // Poll in priority order (CRITICAL first, BULK last)
        for (JobPriority priority : JobPriority.values()) {
            Queue<JobExecution<T>> queue = queues.get(priority);
            JobExecution<T> execution = queue.poll();

            if (execution != null) {
                depthGauges.get(priority).decrementAndGet();
                meterRegistry.counter(queueName + ".job.polled", "priority", priority.toMetricTag()).increment();

                // Record queue wait time
                long waitTimeMs = execution.getAgeMs();
                meterRegistry.timer(queueName + ".job.wait_time", "priority", priority.toMetricTag()).record(waitTimeMs,
                        java.util.concurrent.TimeUnit.MILLISECONDS);

                return execution;
            }
        }

        return null;
    }

    /**
     * Returns the current queue depth for a specific priority.
     */
    public int getDepth(JobPriority priority) {
        return depthGauges.get(priority).get();
    }

    /**
     * Returns the total queue depth across all priorities.
     */
    public int getTotalDepth() {
        return depthGauges.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    /**
     * Clears all queues (used in tests).
     */
    public void clear() {
        for (JobPriority priority : JobPriority.values()) {
            queues.get(priority).clear();
            depthGauges.get(priority).set(0);
        }
    }
}
