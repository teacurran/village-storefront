package villagecompute.storefront.services;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Token bucket rate limiting service for headless API endpoints.
 *
 * <p>
 * Implements in-memory token bucket algorithm to enforce per-client rate limits. Designed to work without Redis (per
 * "No Redis" mandate in Section 6 Safety Net).
 *
 * <p>
 * <strong>Algorithm:</strong>
 * <ol>
 * <li>Each client has a bucket with capacity = rate limit per minute</li>
 * <li>Tokens refill at constant rate (capacity / 60 tokens per second)</li>
 * <li>Each request consumes 1 token</li>
 * <li>Request denied if no tokens available (429 Too Many Requests)</li>
 * </ol>
 *
 * <p>
 * <strong>Limitations (acceptable for single-pod deployments):</strong>
 * <ul>
 * <li>State is in-memory only, not shared across pods</li>
 * <li>Rate limits reset on pod restart</li>
 * <li>Multi-pod deployments will have per-pod limits (N pods = N × limit)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Rate limiting for headless API</li>
 * <li>Architecture: Section 6 Safety Net (No Redis mandate)</li>
 * <li>Architecture: Section 5 Contract Patterns (rate limits per tenant per scope)</li>
 * </ul>
 */
@ApplicationScoped
public class RateLimitService {

    private static final Logger LOG = Logger.getLogger(RateLimitService.class);

    @Inject
    MeterRegistry meterRegistry;

    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Check if request is allowed under rate limit.
     *
     * @param clientId
     *            OAuth client ID
     * @param scope
     *            requested scope
     * @param limitPerMinute
     *            rate limit (requests per minute)
     * @return rate limit result with decision and reset timestamp
     */
    public RateLimitResult checkRateLimit(String clientId, String scope, int limitPerMinute) {
        String key = buildBucketKey(clientId, scope);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(limitPerMinute));

        boolean allowed = bucket.tryConsume();
        Instant resetAt = bucket.getResetAt();

        if (!allowed) {
            LOG.warnf("Rate limit exceeded - clientId=%s, scope=%s, limit=%d/min", clientId, scope, limitPerMinute);
            meterRegistry.counter("headless.rate_limit.exceeded", "client_id", clientId, "scope", scope).increment();
        }

        return new RateLimitResult(allowed, limitPerMinute, bucket.getAvailableTokens(), resetAt);
    }

    /**
     * Reset rate limit bucket for a client (admin operation).
     *
     * @param clientId
     *            OAuth client ID
     * @param scope
     *            scope to reset
     */
    public void resetRateLimit(String clientId, String scope) {
        String key = buildBucketKey(clientId, scope);
        buckets.remove(key);
        LOG.infof("Rate limit bucket reset - clientId=%s, scope=%s", clientId, scope);
    }

    /**
     * Clear all rate limit buckets (admin operation, use sparingly).
     */
    public void clearAllBuckets() {
        int count = buckets.size();
        buckets.clear();
        LOG.infof("Cleared all rate limit buckets - count=%d", count);
    }

    private String buildBucketKey(String clientId, String scope) {
        return clientId + ":" + scope;
    }

    /**
     * Token bucket for rate limiting.
     *
     * <p>
     * Thread-safe implementation using synchronized methods for simplicity. For higher concurrency, could use
     * AtomicReference<BucketState> with CAS loop.
     */
    private static class TokenBucket {
        private final int capacity;
        private final double refillRatePerSecond;
        private double tokens;
        private Instant lastRefill;

        TokenBucket(int capacityPerMinute) {
            this.capacity = capacityPerMinute;
            this.refillRatePerSecond = (double) capacityPerMinute / 60.0;
            this.tokens = capacityPerMinute;
            this.lastRefill = Instant.now();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized int getAvailableTokens() {
            refill();
            return (int) Math.floor(tokens);
        }

        synchronized Instant getResetAt() {
            refill();
            if (tokens >= capacity) {
                return Instant.now();
            }
            // Calculate when bucket will be full
            double tokensNeeded = capacity - tokens;
            long secondsUntilFull = (long) Math.ceil(tokensNeeded / refillRatePerSecond);
            return Instant.now().plusSeconds(secondsUntilFull);
        }

        private void refill() {
            Instant now = Instant.now();
            Duration elapsed = Duration.between(lastRefill, now);
            double tokensToAdd = elapsed.toMillis() / 1000.0 * refillRatePerSecond;

            if (tokensToAdd > 0) {
                tokens = Math.min(capacity, tokens + tokensToAdd);
                lastRefill = now;
            }
        }
    }

    /**
     * Result of rate limit check.
     *
     * @param allowed
     *            true if request is allowed
     * @param limit
     *            rate limit (requests per minute)
     * @param remaining
     *            remaining requests in current window
     * @param resetAt
     *            timestamp when rate limit window resets
     */
    public record RateLimitResult(boolean allowed, int limit, int remaining, Instant resetAt) {
    }
}
