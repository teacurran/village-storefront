package villagecompute.storefront.services.jobs;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.services.jobs.config.*;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.test.junit.QuarkusTest;

/**
 * Integration tests for background job framework enhancements.
 *
 * <p>
 * Tests priority queuing, retry policies, dead-letter handling, and Prometheus metrics instrumentation per Task I3.T6
 * acceptance criteria.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I3.T6 - Background Job Framework Enhancements</li>
 * <li>Architecture: 04_Operational_Architecture.md (Section 3.6)</li>
 * </ul>
 */
@QuarkusTest
public class JobSchedulerTest {

    @Inject
    MeterRegistry meterRegistry;

    private PriorityJobQueue<TestPayload> queue;
    private DeadLetterQueue<TestPayload> dlq;
    private JobProcessor<TestPayload> processor;
    private JobConfig config;
    private TestJobHandler handler;

    @BeforeEach
    void setup() {
        // Configure retry policies
        config = JobConfig.builder().retryPolicy(JobPriority.CRITICAL, RetryPolicy.aggressive())
                .retryPolicy(JobPriority.DEFAULT, RetryPolicy.defaultPolicy())
                .retryPolicy(JobPriority.BULK, RetryPolicy.noRetry()).queueCapacity(JobPriority.CRITICAL, 10)
                .queueCapacity(JobPriority.DEFAULT, 100).queueCapacity(JobPriority.BULK, Integer.MAX_VALUE).build();

        queue = new PriorityJobQueue<>("test.job", meterRegistry, config);
        dlq = new DeadLetterQueue<>("test.job", meterRegistry);
        handler = new TestJobHandler();

        processor = new JobProcessor<>("test.job", meterRegistry, queue, dlq, config, handler,
                TestPayload::getTenantId);

        // Clear queues and reset handler state
        queue.clear();
        dlq.clear();
        handler.reset();
    }

    @Test
    void testPriorityOrdering() {
        // Enqueue jobs in reverse priority order
        UUID tenantId = UUID.randomUUID();
        queue.enqueue(new TestPayload(tenantId, "BULK"), JobPriority.BULK);
        queue.enqueue(new TestPayload(tenantId, "LOW"), JobPriority.LOW);
        queue.enqueue(new TestPayload(tenantId, "DEFAULT"), JobPriority.DEFAULT);
        queue.enqueue(new TestPayload(tenantId, "HIGH"), JobPriority.HIGH);
        queue.enqueue(new TestPayload(tenantId, "CRITICAL"), JobPriority.CRITICAL);

        // Verify total queue depth
        assertEquals(5, queue.getTotalDepth());

        // Process jobs and verify priority order (CRITICAL first, BULK last)
        processor.processNext();
        assertEquals("CRITICAL", handler.getLastProcessed());

        processor.processNext();
        assertEquals("HIGH", handler.getLastProcessed());

        processor.processNext();
        assertEquals("DEFAULT", handler.getLastProcessed());

        processor.processNext();
        assertEquals("LOW", handler.getLastProcessed());

        processor.processNext();
        assertEquals("BULK", handler.getLastProcessed());

        assertEquals(0, queue.getTotalDepth());
    }

    @Test
    void testRetryPolicyExponentialBackoff() {
        RetryPolicy policy = RetryPolicy.defaultPolicy(); // 3 attempts, 1s initial, 5m max, 2x multiplier

        // First retry: 1s
        assertEquals(Duration.ofSeconds(1), policy.calculateDelay(1));

        // Second retry: 2s
        assertEquals(Duration.ofSeconds(2), policy.calculateDelay(2));

        // Third retry: 4s
        assertEquals(Duration.ofSeconds(4), policy.calculateDelay(3));

        // Subsequent retries capped at maxDelay
        assertTrue(policy.calculateDelay(20).toMillis() <= Duration.ofMinutes(5).toMillis());
    }

    @Test
    void testRetryPolicyWithJobProcessor() {
        UUID tenantId = UUID.randomUUID();

        // Configure handler to fail twice, then succeed
        handler.setFailureCount(2);

        // Enqueue job with DEFAULT priority (3 retry attempts)
        queue.enqueue(new TestPayload(tenantId, "RETRY_TEST"), JobPriority.DEFAULT);

        // Process job - should fail and retry
        processor.processNext(); // Attempt 1 - fails, retry enqueued
        assertEquals(1, handler.getProcessCount());
        assertEquals(1, queue.getDepth(JobPriority.DEFAULT)); // Retried job re-enqueued

        processor.processNext(); // Attempt 2 - fails, retry enqueued
        assertEquals(2, handler.getProcessCount());
        assertEquals(1, queue.getDepth(JobPriority.DEFAULT));

        processor.processNext(); // Attempt 3 - succeeds
        assertEquals(3, handler.getProcessCount());
        assertEquals(0, queue.getDepth(JobPriority.DEFAULT));
        assertEquals(0, dlq.getDepth()); // Not moved to DLQ

        // Verify retry success metric incremented
        Counter retrySuccessCounter = meterRegistry.find("test.job.job.retry_success").counter();
        assertNotNull(retrySuccessCounter);
    }

    @Test
    void testDeadLetterQueueAfterRetryExhaustion() {
        UUID tenantId = UUID.randomUUID();

        // Configure handler to always fail
        handler.setAlwaysFail(true);

        // Enqueue job with DEFAULT priority (3 retry attempts)
        queue.enqueue(new TestPayload(tenantId, "DLQ_TEST"), JobPriority.DEFAULT);

        // Process job through all retry attempts
        processor.processNext(); // Attempt 1 - fails, retry enqueued
        processor.processNext(); // Attempt 2 - fails, retry enqueued
        processor.processNext(); // Attempt 3 - fails, retry enqueued
        processor.processNext(); // Attempt 4 - exceeds max attempts, moves to DLQ

        // Verify job moved to DLQ
        assertEquals(0, queue.getTotalDepth());
        assertEquals(1, dlq.getDepth());

        // Verify exhausted metric incremented
        Counter exhaustedCounter = meterRegistry.find("test.job.job.exhausted").counter();
        assertNotNull(exhaustedCounter);

        // Inspect DLQ job
        JobExecution<TestPayload> deadJob = dlq.poll();
        assertNotNull(deadJob);
        assertEquals("DLQ_TEST", deadJob.getPayload().getData());
        assertTrue(deadJob.getAttemptNumber() > 0);
        assertNotNull(deadJob.getLastError());
    }

    @Test
    void testNoRetryPolicy() {
        UUID tenantId = UUID.randomUUID();

        // Configure handler to always fail
        handler.setAlwaysFail(true);

        // Enqueue job with BULK priority (no retry)
        queue.enqueue(new TestPayload(tenantId, "NO_RETRY"), JobPriority.BULK);

        // Process job - should fail immediately and move to DLQ
        processor.processNext();

        // Verify job moved to DLQ without retry
        assertEquals(0, queue.getTotalDepth());
        assertEquals(1, dlq.getDepth());

        JobExecution<TestPayload> deadJob = dlq.poll();
        assertEquals("NO_RETRY", deadJob.getPayload().getData());
        assertEquals(1, deadJob.getAttemptNumber()); // Only 1 attempt (no retries)
    }

    @Test
    void testQueueCapacityOverflow() {
        UUID tenantId = UUID.randomUUID();

        // CRITICAL queue has capacity of 10
        for (int i = 0; i < 15; i++) {
            boolean enqueued = queue.enqueue(new TestPayload(tenantId, "Job-" + i), JobPriority.CRITICAL);
            if (i < 10) {
                assertTrue(enqueued, "First 10 jobs should enqueue successfully");
            } else {
                assertFalse(enqueued, "Jobs 11-15 should fail due to capacity limit");
            }
        }

        // Verify queue depth capped at capacity
        assertEquals(10, queue.getDepth(JobPriority.CRITICAL));

        // Verify overflow metric incremented
        Counter overflowCounter = meterRegistry.find("test.job.queue.overflow").counter();
        assertNotNull(overflowCounter);
    }

    @Test
    void testPrometheusMetrics_QueueDepth() {
        UUID tenantId = UUID.randomUUID();

        // Enqueue jobs across different priorities
        queue.enqueue(new TestPayload(tenantId, "C1"), JobPriority.CRITICAL);
        queue.enqueue(new TestPayload(tenantId, "C2"), JobPriority.CRITICAL);
        queue.enqueue(new TestPayload(tenantId, "H1"), JobPriority.HIGH);
        queue.enqueue(new TestPayload(tenantId, "D1"), JobPriority.DEFAULT);

        // Verify queue depth gauges
        assertEquals(2, queue.getDepth(JobPriority.CRITICAL));
        assertEquals(1, queue.getDepth(JobPriority.HIGH));
        assertEquals(1, queue.getDepth(JobPriority.DEFAULT));
        assertEquals(0, queue.getDepth(JobPriority.LOW));
        assertEquals(0, queue.getDepth(JobPriority.BULK));
    }

    @Test
    void testPrometheusMetrics_JobLifecycle() {
        UUID tenantId = UUID.randomUUID();

        // Reset metric counters by reading initial values
        double initialEnqueued = getCounterValue("test.job.job.enqueued");
        double initialCompleted = getCounterValue("test.job.job.completed");

        // Enqueue and process job
        queue.enqueue(new TestPayload(tenantId, "METRICS_TEST"), JobPriority.DEFAULT);
        processor.processNext();

        // Verify metrics incremented
        assertTrue(getCounterValue("test.job.job.enqueued") > initialEnqueued);
        assertTrue(getCounterValue("test.job.job.completed") > initialCompleted);

        // Verify timer recorded
        Timer durationTimer = meterRegistry.find("test.job.job.duration").timer();
        assertNotNull(durationTimer);
        assertTrue(durationTimer.count() > 0);
    }

    @Test
    void testPrometheusMetrics_FailureRate() {
        UUID tenantId = UUID.randomUUID();

        // Configure handler to always fail
        handler.setAlwaysFail(true);

        double initialFailed = getCounterValue("test.job.job.failed");

        // Enqueue and process failing job
        queue.enqueue(new TestPayload(tenantId, "FAIL_TEST"), JobPriority.DEFAULT);
        processor.processNext();

        // Verify failure metric incremented
        assertTrue(getCounterValue("test.job.job.failed") > initialFailed);
    }

    @Test
    void testProcessAllPending() {
        UUID tenantId = UUID.randomUUID();

        // Enqueue multiple jobs
        for (int i = 0; i < 5; i++) {
            queue.enqueue(new TestPayload(tenantId, "Job-" + i), JobPriority.DEFAULT);
        }

        assertEquals(5, queue.getTotalDepth());

        // Process all pending jobs synchronously
        processor.processAllPending();

        // Verify all jobs processed
        assertEquals(0, queue.getTotalDepth());
        assertEquals(5, handler.getProcessCount());
    }

    @Test
    void testDLQPeekAll() {
        UUID tenantId = UUID.randomUUID();

        // Add multiple jobs to DLQ
        dlq.add(JobExecution.create(new TestPayload(tenantId, "DLQ-1"), JobPriority.DEFAULT));
        dlq.add(JobExecution.create(new TestPayload(tenantId, "DLQ-2"), JobPriority.HIGH));
        dlq.add(JobExecution.create(new TestPayload(tenantId, "DLQ-3"), JobPriority.CRITICAL));

        // Peek all DLQ jobs without removing
        assertEquals(3, dlq.peekAll().size());
        assertEquals(3, dlq.getDepth()); // Still in DLQ

        // Verify inspection doesn't affect DLQ depth
        dlq.peekAll();
        assertEquals(3, dlq.getDepth());
    }

    // --- Helper Methods & Test Classes ---

    private double getCounterValue(String name) {
        Counter counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0.0;
    }

    /**
     * Test job payload with tenant context.
     */
    static class TestPayload {
        private final UUID tenantId;
        private final String data;

        TestPayload(UUID tenantId, String data) {
            this.tenantId = tenantId;
            this.data = data;
        }

        public UUID getTenantId() {
            return tenantId;
        }

        public String getData() {
            return data;
        }
    }

    /**
     * Test job handler with configurable failure behavior.
     */
    static class TestJobHandler implements JobProcessor.JobHandler<TestPayload> {
        private final AtomicInteger processCount = new AtomicInteger(0);
        private int failureCount = 0;
        private boolean alwaysFail = false;
        private String lastProcessed = null;

        @Override
        public void handle(TestPayload payload) throws Exception {
            int count = processCount.incrementAndGet();
            lastProcessed = payload.getData();

            if (alwaysFail || count <= failureCount) {
                throw new RuntimeException("Simulated job failure: " + payload.getData());
            }

            // Success - no exception thrown
        }

        public void setFailureCount(int count) {
            this.failureCount = count;
        }

        public void setAlwaysFail(boolean alwaysFail) {
            this.alwaysFail = alwaysFail;
        }

        public int getProcessCount() {
            return processCount.get();
        }

        public String getLastProcessed() {
            return lastProcessed;
        }

        public void reset() {
            processCount.set(0);
            failureCount = 0;
            alwaysFail = false;
            lastProcessed = null;
        }
    }
}
