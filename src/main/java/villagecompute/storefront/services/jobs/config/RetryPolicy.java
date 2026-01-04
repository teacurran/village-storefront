package villagecompute.storefront.services.jobs.config;

import java.time.Duration;

/**
 * Retry policy configuration for background jobs.
 *
 * <p>
 * Defines retry behavior including maximum attempts, backoff strategy, and dead-letter handling. Referenced in
 * Operational Architecture §3.6 (Background Processing).
 *
 * <p>
 * Example usage:
 * 
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.builder().maxAttempts(5).initialDelay(Duration.ofSeconds(1))
 *         .maxDelay(Duration.ofMinutes(5)).backoffMultiplier(2.0).build();
 * }</pre>
 */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double backoffMultiplier;
    private final boolean exponentialBackoff;

    private RetryPolicy(Builder builder) {
        this.maxAttempts = builder.maxAttempts;
        this.initialDelay = builder.initialDelay;
        this.maxDelay = builder.maxDelay;
        this.backoffMultiplier = builder.backoffMultiplier;
        this.exponentialBackoff = builder.exponentialBackoff;
    }

    /**
     * Calculates the delay before the next retry attempt using exponential backoff.
     *
     * @param attemptNumber
     *            the attempt number (1-based, 1 = first retry)
     * @return the delay duration before retrying
     */
    public Duration calculateDelay(int attemptNumber) {
        if (!exponentialBackoff || attemptNumber <= 0) {
            return initialDelay;
        }

        long delayMs = (long) (initialDelay.toMillis() * Math.pow(backoffMultiplier, attemptNumber - 1));
        long cappedDelayMs = Math.min(delayMs, maxDelay.toMillis());
        return Duration.ofMillis(cappedDelayMs);
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public double getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public boolean isExponentialBackoff() {
        return exponentialBackoff;
    }

    /**
     * Creates a new RetryPolicy builder.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Default retry policy: 3 attempts, exponential backoff from 1s to 5m with 2x multiplier.
     */
    public static RetryPolicy defaultPolicy() {
        return builder().maxAttempts(3).initialDelay(Duration.ofSeconds(1)).maxDelay(Duration.ofMinutes(5))
                .backoffMultiplier(2.0).exponentialBackoff(true).build();
    }

    /**
     * No retry policy: jobs fail immediately without retry.
     */
    public static RetryPolicy noRetry() {
        return builder().maxAttempts(0).initialDelay(Duration.ZERO).maxDelay(Duration.ZERO).backoffMultiplier(1.0)
                .exponentialBackoff(false).build();
    }

    /**
     * Aggressive retry policy: 5 attempts, faster backoff for critical jobs.
     */
    public static RetryPolicy aggressive() {
        return builder().maxAttempts(5).initialDelay(Duration.ofMillis(500)).maxDelay(Duration.ofSeconds(30))
                .backoffMultiplier(1.5).exponentialBackoff(true).build();
    }

    public static class Builder {
        private int maxAttempts = 3;
        private Duration initialDelay = Duration.ofSeconds(1);
        private Duration maxDelay = Duration.ofMinutes(5);
        private double backoffMultiplier = 2.0;
        private boolean exponentialBackoff = true;

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public Builder initialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
            return this;
        }

        public Builder maxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
            return this;
        }

        public Builder backoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
            return this;
        }

        public Builder exponentialBackoff(boolean exponentialBackoff) {
            this.exponentialBackoff = exponentialBackoff;
            return this;
        }

        public RetryPolicy build() {
            if (maxAttempts < 0) {
                throw new IllegalArgumentException("maxAttempts must be >= 0");
            }
            if (backoffMultiplier <= 0) {
                throw new IllegalArgumentException("backoffMultiplier must be > 0");
            }
            return new RetryPolicy(this);
        }
    }
}
