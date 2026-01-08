package villagecompute.storefront.services.jobs.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Dead-letter queue for jobs that exceeded retry limits or failed fatally.
 *
 * <p>
 * Preserves failed jobs for manual inspection with full execution history (attempt counts, error messages, timestamps).
 * Exposes Prometheus metrics for monitoring DLQ depth and throughput.
 *
 * <p>
 * Referenced in Operational Architecture §3.6 (Background Processing).
 *
 * @param <T>
 *            the job payload type
 */
public final class DeadLetterQueue<T> {
    private static final Logger LOG = Logger.getLogger(DeadLetterQueue.class);

    private final String queueName;
    private final MeterRegistry meterRegistry;
    private final Queue<JobExecution<T>> queue;
    private final AtomicInteger depthGauge;

    /**
     * Creates a new dead-letter queue.
     *
     * @param queueName
     *            the queue name for metrics (e.g., "reporting.refresh.dlq")
     * @param meterRegistry
     *            the Micrometer registry for Prometheus metrics
     */
    public DeadLetterQueue(String queueName, MeterRegistry meterRegistry) {
        this.queueName = queueName;
        this.meterRegistry = meterRegistry;
        this.queue = new ConcurrentLinkedQueue<>();
        this.depthGauge = new AtomicInteger(0);

        // Register DLQ depth gauge
        meterRegistry.gauge(queueName + ".dlq.depth", depthGauge, AtomicInteger::get);
    }

    /**
     * Adds a failed job execution to the dead-letter queue.
     *
     * @param execution
     *            the failed job execution with error context
     */
    public void add(JobExecution<T> execution) {
        queue.offer(execution);
        depthGauge.incrementAndGet();

        meterRegistry.counter(queueName + ".dlq.added", "priority", execution.getPriority().toMetricTag()).increment();

        LOG.errorf("Job moved to DLQ - executionId=%s, priority=%s, attempts=%d, lastError=%s",
                execution.getExecutionId(), execution.getPriority(), execution.getAttemptNumber(),
                execution.getLastError());
    }

    /**
     * Retrieves a failed job from the DLQ for manual inspection/reprocessing.
     *
     * @return the next job execution, or null if DLQ is empty
     */
    public JobExecution<T> poll() {
        JobExecution<T> execution = queue.poll();
        if (execution != null) {
            depthGauge.decrementAndGet();
            meterRegistry.counter(queueName + ".dlq.removed").increment();
        }
        return execution;
    }

    /**
     * Returns all failed jobs currently in the DLQ (for admin inspection).
     */
    public List<JobExecution<T>> peekAll() {
        return new ArrayList<>(queue);
    }

    /**
     * Returns the current DLQ depth.
     */
    public int getDepth() {
        return depthGauge.get();
    }

    /**
     * Clears the DLQ (used in tests or after manual intervention).
     */
    public void clear() {
        queue.clear();
        depthGauge.set(0);
        LOG.infof("DLQ cleared for queue: %s", queueName);
    }
}
