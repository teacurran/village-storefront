package villagecompute.storefront.services.jobs.config;

/**
 * Job priority levels for background processing.
 *
 * <p>
 * Priorities determine processing order and resource allocation:
 * <ul>
 * <li>CRITICAL: Payment webhooks, order confirmations (target: &lt;1s latency)</li>
 * <li>HIGH: Notifications, inventory updates (target: &lt;5s latency)</li>
 * <li>DEFAULT: Report generation, email processing (target: &lt;30s latency)</li>
 * <li>LOW: Analytics aggregation, cache warming (target: &lt;2m latency)</li>
 * <li>BULK: Archive operations, data migrations (target: best-effort)</li>
 * </ul>
 *
 * <p>
 * Referenced in Operational Architecture §3.6.
 */
public enum JobPriority {
    CRITICAL(0, 1000), HIGH(1, 5000), DEFAULT(2, 30000), LOW(3, 120000), BULK(4, Integer.MAX_VALUE);

    private final int order;
    private final long targetLatencyMs;

    JobPriority(int order, long targetLatencyMs) {
        this.order = order;
        this.targetLatencyMs = targetLatencyMs;
    }

    /**
     * Returns the numerical order for priority comparison (lower is higher priority).
     */
    public int getOrder() {
        return order;
    }

    /**
     * Returns the target latency in milliseconds for this priority level.
     */
    public long getTargetLatencyMs() {
        return targetLatencyMs;
    }

    /**
     * Returns the metric tag value for this priority.
     */
    public String toMetricTag() {
        return name().toLowerCase();
    }
}
