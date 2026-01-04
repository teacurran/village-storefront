package villagecompute.storefront.services.jobs.config;

import java.time.Duration;
import java.util.UUID;

import org.jboss.logging.Logger;

import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Generic job processor with retry logic, dead-letter handling, and metrics.
 *
 * <p>
 * Implements the retry/backoff/DLQ workflow defined in Operational Architecture §3.6:
 * <ol>
 * <li>Poll job from priority queue</li>
 * <li>Restore tenant context from payload</li>
 * <li>Execute job via JobHandler</li>
 * <li>On failure: check retry policy, enqueue retry or move to DLQ</li>
 * <li>Emit metrics for all lifecycle events</li>
 * </ol>
 *
 * @param <T>
 *            the job payload type (must contain tenantId for context restoration)
 */
public final class JobProcessor<T> {
    private static final Logger LOG = Logger.getLogger(JobProcessor.class);

    private final String processorName;
    private final MeterRegistry meterRegistry;
    private final PriorityJobQueue<T> queue;
    private final DeadLetterQueue<T> dlq;
    private final JobConfig config;
    private final JobHandler<T> handler;
    private final TenantContextExtractor<T> tenantExtractor;

    /**
     * Creates a new job processor.
     *
     * @param processorName
     *            the processor name for metrics (e.g., "reporting.refresh")
     * @param meterRegistry
     *            the Micrometer registry
     * @param queue
     *            the priority job queue
     * @param dlq
     *            the dead-letter queue
     * @param config
     *            the job configuration (retry policies)
     * @param handler
     *            the job handler that performs the actual work
     * @param tenantExtractor
     *            extracts tenant ID from payload for context restoration
     */
    public JobProcessor(String processorName, MeterRegistry meterRegistry, PriorityJobQueue<T> queue,
            DeadLetterQueue<T> dlq, JobConfig config, JobHandler<T> handler,
            TenantContextExtractor<T> tenantExtractor) {
        this.processorName = processorName;
        this.meterRegistry = meterRegistry;
        this.queue = queue;
        this.dlq = dlq;
        this.config = config;
        this.handler = handler;
        this.tenantExtractor = tenantExtractor;
    }

    /**
     * Processes the next job from the queue.
     *
     * @return true if a job was processed, false if queue was empty
     */
    public boolean processNext() {
        JobExecution<T> execution = queue.poll();
        if (execution == null) {
            return false;
        }

        processExecution(execution);
        return true;
    }

    /**
     * Processes all pending jobs in the queue (used for synchronous testing).
     */
    public void processAllPending() {
        while (processNext()) {
            // Continue until queue is empty
        }
    }

    private void processExecution(JobExecution<T> execution) {
        JobPriority priority = execution.getPriority();
        UUID tenantId = tenantExtractor.extractTenantId(execution.getPayload());
        UUID previousTenantId = TenantContext.hasContext() ? TenantContext.getCurrentTenantId() : null;

        Timer.Sample sample = Timer.start(meterRegistry);
        meterRegistry.counter(processorName + ".job.started", "priority", priority.toMetricTag()).increment();

        try {
            // Restore tenant context (optional - some jobs may not have tenant context)
            if (tenantId != null) {
                try {
                    TenantContext.setCurrentTenantId(tenantId);
                } catch (IllegalArgumentException e) {
                    // Tenant not found - log warning but continue (useful for tests with mock tenants)
                    LOG.warnf("Tenant not found: %s - processing job without tenant context", tenantId);
                }
            }

            // Execute job handler
            handler.handle(execution.getPayload());

            // Record success metrics
            sample.stop(meterRegistry.timer(processorName + ".job.duration", "priority", priority.toMetricTag(),
                    "status", "success"));
            meterRegistry.counter(processorName + ".job.completed", "priority", priority.toMetricTag()).increment();

            if (execution.getAttemptNumber() > 0) {
                meterRegistry.counter(processorName + ".job.retry_success", "priority", priority.toMetricTag(),
                        "attempt", String.valueOf(execution.getAttemptNumber())).increment();
            }

        } catch (Exception e) {
            handleFailure(execution, e, sample);
        } finally {
            // Restore previous tenant context
            if (previousTenantId != null) {
                try {
                    TenantContext.setCurrentTenantId(previousTenantId);
                } catch (IllegalArgumentException e) {
                    LOG.warnf("Failed to restore previous tenant context: %s", previousTenantId);
                    TenantContext.clear();
                }
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleFailure(JobExecution<T> execution, Exception error, Timer.Sample sample) {
        JobPriority priority = execution.getPriority();
        int attemptNumber = execution.getAttemptNumber() + 1;
        String errorMessage = error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();

        LOG.errorf(error, "Job failed - executionId=%s, priority=%s, attempt=%d", execution.getExecutionId(), priority,
                attemptNumber);

        // Record failure metrics
        sample.stop(meterRegistry.timer(processorName + ".job.duration", "priority", priority.toMetricTag(), "status",
                "failed"));
        meterRegistry.counter(processorName + ".job.failed", "priority", priority.toMetricTag(), "attempt",
                String.valueOf(attemptNumber)).increment();

        // Check retry policy
        RetryPolicy retryPolicy = config.getRetryPolicy(priority);
        if (attemptNumber <= retryPolicy.getMaxAttempts()) {
            // Schedule retry with backoff
            JobExecution<T> retryExecution = execution.withRetry(errorMessage);
            Duration delay = retryPolicy.calculateDelay(attemptNumber);

            LOG.infof("Scheduling retry for job - executionId=%s, attempt=%d/%d, delay=%s", execution.getExecutionId(),
                    attemptNumber, retryPolicy.getMaxAttempts(), delay);

            // For MVP: re-enqueue immediately (TODO: implement delayed scheduling)
            boolean enqueued = queue.enqueueExecution(retryExecution);
            if (enqueued) {
                meterRegistry.counter(processorName + ".job.retried", "priority", priority.toMetricTag(), "attempt",
                        String.valueOf(attemptNumber)).increment();
            } else {
                // Queue at capacity, move to DLQ
                dlq.add(retryExecution);
            }
        } else {
            // Exceeded retry limit, move to DLQ
            JobExecution<T> deadExecution = execution.withRetry(errorMessage);
            dlq.add(deadExecution);
            meterRegistry.counter(processorName + ".job.exhausted", "priority", priority.toMetricTag()).increment();
        }
    }

    /**
     * Extracts tenant ID from job payload for context restoration.
     */
    @FunctionalInterface
    public interface TenantContextExtractor<T> {
        UUID extractTenantId(T payload);
    }

    /**
     * Handles the actual job execution logic.
     */
    @FunctionalInterface
    public interface JobHandler<T> {
        void handle(T payload) throws Exception;
    }
}
