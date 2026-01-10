package villagecompute.storefront.reporting.aggregators;

import villagecompute.storefront.data.models.DomainEvent;

/**
 * Base interface for domain event aggregators.
 *
 * <p>
 * Each aggregator processes specific event types and updates corresponding aggregate tables. Implementations must be
 * idempotent to handle checkpoint replay scenarios.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Pattern: Event-driven aggregation with UPSERT idempotency</li>
 * </ul>
 */
public interface EventAggregator {

    /**
     * Process a domain event and update corresponding aggregate table.
     *
     * <p>
     * Implementations MUST be idempotent (safe to call multiple times with same event).
     *
     * @param event
     *            domain event to process
     */
    void processEvent(DomainEvent event);
}
