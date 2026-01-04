package villagecompute.storefront.services.jobs.config;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wrapper for job payloads with execution metadata for retry/DLQ handling.
 *
 * <p>
 * Tracks attempts, timestamps, and errors to support retry policies and dead-letter queue processing. Referenced in
 * Operational Architecture §3.6 (Background Processing).
 *
 * @param <T>
 *            the job payload type
 */
public final class JobExecution<T> {
    private final UUID executionId;
    private final T payload;
    private final JobPriority priority;
    private final OffsetDateTime enqueuedAt;
    private final int attemptNumber;
    private final String lastError;
    private final OffsetDateTime lastAttemptAt;

    private JobExecution(Builder<T> builder) {
        this.executionId = builder.executionId;
        this.payload = builder.payload;
        this.priority = builder.priority;
        this.enqueuedAt = builder.enqueuedAt;
        this.attemptNumber = builder.attemptNumber;
        this.lastError = builder.lastError;
        this.lastAttemptAt = builder.lastAttemptAt;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public T getPayload() {
        return payload;
    }

    public JobPriority getPriority() {
        return priority;
    }

    public OffsetDateTime getEnqueuedAt() {
        return enqueuedAt;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getLastError() {
        return lastError;
    }

    public OffsetDateTime getLastAttemptAt() {
        return lastAttemptAt;
    }

    /**
     * Returns the age of this job in milliseconds.
     */
    public long getAgeMs() {
        return OffsetDateTime.now().toInstant().toEpochMilli() - enqueuedAt.toInstant().toEpochMilli();
    }

    /**
     * Creates a new execution with incremented attempt count and error information.
     */
    public JobExecution<T> withRetry(String errorMessage) {
        return new Builder<T>().executionId(this.executionId).payload(this.payload).priority(this.priority)
                .enqueuedAt(this.enqueuedAt).attemptNumber(this.attemptNumber + 1).lastError(errorMessage)
                .lastAttemptAt(OffsetDateTime.now()).build();
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Creates a new job execution for a fresh payload.
     */
    public static <T> JobExecution<T> create(T payload, JobPriority priority) {
        return new Builder<T>().executionId(UUID.randomUUID()).payload(payload).priority(priority)
                .enqueuedAt(OffsetDateTime.now()).attemptNumber(0).build();
    }

    public static class Builder<T> {
        private UUID executionId;
        private T payload;
        private JobPriority priority;
        private OffsetDateTime enqueuedAt;
        private int attemptNumber = 0;
        private String lastError;
        private OffsetDateTime lastAttemptAt;

        public Builder<T> executionId(UUID executionId) {
            this.executionId = executionId;
            return this;
        }

        public Builder<T> payload(T payload) {
            this.payload = payload;
            return this;
        }

        public Builder<T> priority(JobPriority priority) {
            this.priority = priority;
            return this;
        }

        public Builder<T> enqueuedAt(OffsetDateTime enqueuedAt) {
            this.enqueuedAt = enqueuedAt;
            return this;
        }

        public Builder<T> attemptNumber(int attemptNumber) {
            this.attemptNumber = attemptNumber;
            return this;
        }

        public Builder<T> lastError(String lastError) {
            this.lastError = lastError;
            return this;
        }

        public Builder<T> lastAttemptAt(OffsetDateTime lastAttemptAt) {
            this.lastAttemptAt = lastAttemptAt;
            return this;
        }

        public JobExecution<T> build() {
            if (executionId == null) {
                executionId = UUID.randomUUID();
            }
            if (enqueuedAt == null) {
                enqueuedAt = OffsetDateTime.now();
            }
            return new JobExecution<>(this);
        }
    }
}
