package villagecompute.storefront.services.jobs.config;

import java.util.EnumMap;
import java.util.Map;

/**
 * Configuration for the background job framework.
 *
 * <p>
 * Defines retry policies and queue configurations per job priority level. Referenced in Operational Architecture §3.6
 * (Background Processing).
 *
 * <p>
 * Default policies:
 * <ul>
 * <li>CRITICAL: Aggressive retry (5 attempts, fast backoff)</li>
 * <li>HIGH: Default retry (3 attempts, exponential backoff)</li>
 * <li>DEFAULT: Default retry (3 attempts, exponential backoff)</li>
 * <li>LOW: Default retry (3 attempts, exponential backoff)</li>
 * <li>BULK: No retry (fail fast, manual inspection)</li>
 * </ul>
 */
public final class JobConfig {
    private final Map<JobPriority, RetryPolicy> retryPolicies;
    private final Map<JobPriority, Integer> queueCapacities;

    private JobConfig(Builder builder) {
        this.retryPolicies = new EnumMap<>(builder.retryPolicies);
        this.queueCapacities = new EnumMap<>(builder.queueCapacities);
    }

    public RetryPolicy getRetryPolicy(JobPriority priority) {
        return retryPolicies.getOrDefault(priority, RetryPolicy.defaultPolicy());
    }

    public int getQueueCapacity(JobPriority priority) {
        return queueCapacities.getOrDefault(priority, Integer.MAX_VALUE);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the default job configuration matching architecture guidelines.
     */
    public static JobConfig defaults() {
        return builder().retryPolicy(JobPriority.CRITICAL, RetryPolicy.aggressive())
                .retryPolicy(JobPriority.HIGH, RetryPolicy.defaultPolicy())
                .retryPolicy(JobPriority.DEFAULT, RetryPolicy.defaultPolicy())
                .retryPolicy(JobPriority.LOW, RetryPolicy.defaultPolicy())
                .retryPolicy(JobPriority.BULK, RetryPolicy.noRetry()).queueCapacity(JobPriority.CRITICAL, 1000)
                .queueCapacity(JobPriority.HIGH, 5000).queueCapacity(JobPriority.DEFAULT, 10000)
                .queueCapacity(JobPriority.LOW, 10000).queueCapacity(JobPriority.BULK, Integer.MAX_VALUE).build();
    }

    public static class Builder {
        private final Map<JobPriority, RetryPolicy> retryPolicies = new EnumMap<>(JobPriority.class);
        private final Map<JobPriority, Integer> queueCapacities = new EnumMap<>(JobPriority.class);

        public Builder retryPolicy(JobPriority priority, RetryPolicy policy) {
            retryPolicies.put(priority, policy);
            return this;
        }

        public Builder queueCapacity(JobPriority priority, int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("Queue capacity must be > 0");
            }
            queueCapacities.put(priority, capacity);
            return this;
        }

        public JobConfig build() {
            return new JobConfig(this);
        }
    }
}
