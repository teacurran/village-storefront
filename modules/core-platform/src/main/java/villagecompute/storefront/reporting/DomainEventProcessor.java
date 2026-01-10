package villagecompute.storefront.reporting;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.ReportingCheckpoint;
import villagecompute.storefront.reporting.aggregators.ConsignmentAggregator;
import villagecompute.storefront.reporting.aggregators.InventoryAggregator;
import villagecompute.storefront.reporting.aggregators.LoyaltyAggregator;
import villagecompute.storefront.reporting.aggregators.SalesAggregator;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Domain event processor with checkpoint-based watermarking for idempotent aggregate processing.
 *
 * <p>
 * Polls domain_events table and routes events to appropriate aggregators (sales, inventory, loyalty, consignment).
 * Maintains watermark checkpoints to enable crash recovery without duplicate processing.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Architecture: Watermark pattern for streaming idempotency</li>
 * <li>Design: docs/architecture/datamodel_tenancy_narrative.md (Domain Events)</li>
 * </ul>
 */
@ApplicationScoped
public class DomainEventProcessor {

    private static final Logger LOG = Logger.getLogger(DomainEventProcessor.class);

    private static final String PROCESSOR_NAME = "domain_event_aggregator";

    @Inject
    SalesAggregator salesAggregator;

    @Inject
    InventoryAggregator inventoryAggregator;

    @Inject
    LoyaltyAggregator loyaltyAggregator;

    @Inject
    ConsignmentAggregator consignmentAggregator;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    jakarta.persistence.EntityManager entityManager;

    @ConfigProperty(
            name = "reporting.events.batch_size",
            defaultValue = "1000")
    int batchSize;

    @ConfigProperty(
            name = "reporting.events.lookback_minutes",
            defaultValue = "5")
    int lookbackMinutes;

    /**
     * Process next batch of domain events.
     *
     * @return number of events processed
     */
    @Transactional
    public int processEvents() {
        try {
            // Get or create checkpoint
            ReportingCheckpoint checkpoint = getOrCreateCheckpoint();

            // Calculate watermark with lookback window (for clock skew tolerance)
            OffsetDateTime watermark = checkpoint.lastProcessedAt.minusMinutes(lookbackMinutes);

            // Fetch next batch of events
            List<DomainEvent> events = DomainEvent
                    .find("occurredAt > ?1 ORDER BY occurredAt ASC", watermark)
                    .page(0, batchSize)
                    .list();

            if (events.isEmpty()) {
                LOG.debugf("No new events to process since watermark %s", watermark);
                return 0;
            }

            LOG.infof("Processing %d domain events (watermark=%s)", events.size(), watermark);

            // Route each event to appropriate aggregator
            int processed = 0;
            OffsetDateTime latestTimestamp = watermark;

            for (DomainEvent event : events) {
                try {
                    // Check if event already processed (idempotency - prevents double-counting in lookback window)
                    if (isEventAlreadyProcessed(event.id)) {
                        LOG.debugf("Skipping already processed event - eventId=%s", event.id);
                        continue;
                    }

                    processEvent(event);

                    // Mark event as processed (idempotency tracking)
                    markEventAsProcessed(event.id);

                    processed++;

                    if (event.occurredAt.isAfter(latestTimestamp)) {
                        latestTimestamp = event.occurredAt;
                    }
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to process event - eventId=%s, eventType=%s, aggregateType=%s", event.id,
                            event.eventType, event.aggregateType);

                    checkpoint.lastError = String.format("Event %s failed: %s", event.id, e.getMessage());
                    checkpoint.persist();

                    // Continue processing remaining events (skip failures)
                    meterRegistry.counter("reporting.events.processing_errors", Tags.of("event_type", event.eventType))
                            .increment();
                }
            }

            // Update checkpoint (only if we processed events successfully)
            if (processed > 0) {
                checkpoint.lastProcessedAt = latestTimestamp;
                checkpoint.eventsProcessed += processed;
                checkpoint.lastError = null;
                checkpoint.persist();

                LOG.infof("Updated checkpoint - processed=%d, watermark=%s, totalProcessed=%d", processed,
                        latestTimestamp, checkpoint.eventsProcessed);
            }

            // Emit metrics
            meterRegistry.counter("reporting.events.processed").increment(processed);
            registerLagMetric();

            return processed;

        } catch (Exception e) {
            LOG.error("Domain event processing failed", e);
            meterRegistry.counter("reporting.events.batch_errors").increment();
            throw e;
        }
    }

    /**
     * Route event to appropriate aggregator based on event type.
     *
     * @param event
     *            domain event
     */
    private void processEvent(DomainEvent event) {
        switch (event.eventType) {
            case "ORDER_PLACED":
            case "ORDER_COMPLETED":
                salesAggregator.processEvent(event);
                break;

            case "INVENTORY_ADJUSTED":
            case "INVENTORY_RECEIVED":
            case "INVENTORY_TRANSFERRED":
                inventoryAggregator.processEvent(event);
                break;

            case "LOYALTY_EARNED":
            case "LOYALTY_REDEEMED":
            case "LOYALTY_TIER_CHANGED":
                loyaltyAggregator.processEvent(event);
                break;

            case "CONSIGNMENT_SOLD":
            case "CONSIGNMENT_RETURNED":
                consignmentAggregator.processEvent(event);
                break;

            default:
                // Unknown event type - log and skip
                LOG.debugf("Ignoring unknown event type: %s", event.eventType);
        }
    }

    /**
     * Get or create checkpoint for this processor.
     *
     * @return checkpoint
     */
    private ReportingCheckpoint getOrCreateCheckpoint() {
        ReportingCheckpoint checkpoint = ReportingCheckpoint.findByProcessorName(PROCESSOR_NAME);

        if (checkpoint == null) {
            // Initialize checkpoint (start from 1 hour ago to avoid processing entire history)
            checkpoint = new ReportingCheckpoint();
            checkpoint.processorName = PROCESSOR_NAME;
            checkpoint.lastProcessedAt = OffsetDateTime.now().minusHours(1);
            checkpoint.eventsProcessed = 0L;
            checkpoint.persist();

            LOG.infof("Created new checkpoint - processorName=%s, watermark=%s", PROCESSOR_NAME,
                    checkpoint.lastProcessedAt);
        }

        return checkpoint;
    }

    /**
     * Register Prometheus gauge for event lag (seconds behind real-time).
     */
    private void registerLagMetric() {
        Gauge.builder("reporting.events.lag_seconds", this::calculateLagSeconds)
                .description("Lag in seconds between current time and last processed event")
                .register(meterRegistry);
    }

    /**
     * Calculate lag in seconds between current time and last processed event.
     *
     * @return lag in seconds
     */
    private double calculateLagSeconds() {
        ReportingCheckpoint checkpoint = ReportingCheckpoint.findByProcessorName(PROCESSOR_NAME);
        if (checkpoint == null) {
            return 0.0;
        }

        OffsetDateTime now = OffsetDateTime.now();
        return Duration.between(checkpoint.lastProcessedAt, now).getSeconds();
    }

    /**
     * Get current processing lag for monitoring.
     *
     * @return lag duration
     */
    public Duration getCurrentLag() {
        ReportingCheckpoint checkpoint = ReportingCheckpoint.findByProcessorName(PROCESSOR_NAME);
        if (checkpoint == null) {
            return Duration.ZERO;
        }

        return Duration.between(checkpoint.lastProcessedAt, OffsetDateTime.now());
    }

    /**
     * Get total events processed by this processor.
     *
     * @return event count
     */
    public long getTotalEventsProcessed() {
        ReportingCheckpoint checkpoint = ReportingCheckpoint.findByProcessorName(PROCESSOR_NAME);
        return checkpoint != null ? checkpoint.eventsProcessed : 0L;
    }

    /**
     * Reset checkpoint to specific timestamp (for testing/recovery).
     *
     * @param timestamp
     *            new watermark
     */
    @Transactional
    public void resetCheckpoint(OffsetDateTime timestamp) {
        ReportingCheckpoint checkpoint = getOrCreateCheckpoint();
        checkpoint.lastProcessedAt = timestamp;
        checkpoint.persist();

        LOG.warnf("Checkpoint manually reset - processorName=%s, newWatermark=%s", PROCESSOR_NAME, timestamp);
    }

    /**
     * Check if event has already been processed (idempotency check).
     *
     * @param eventId
     *            domain event UUID
     * @return true if event already processed
     */
    private boolean isEventAlreadyProcessed(java.util.UUID eventId) {
        Long count = (Long) entityManager
                .createNativeQuery("SELECT COUNT(*) FROM processed_domain_events WHERE event_id = :eventId AND processor_name = :processorName")
                .setParameter("eventId", eventId)
                .setParameter("processorName", PROCESSOR_NAME)
                .getSingleResult();

        return count != null && count > 0;
    }

    /**
     * Mark event as processed (idempotency tracking).
     *
     * @param eventId
     *            domain event UUID
     */
    private void markEventAsProcessed(java.util.UUID eventId) {
        entityManager
                .createNativeQuery("INSERT INTO processed_domain_events (event_id, processor_name, processed_at) VALUES (:eventId, :processorName, NOW()) ON CONFLICT (event_id) DO NOTHING")
                .setParameter("eventId", eventId)
                .setParameter("processorName", PROCESSOR_NAME)
                .executeUpdate();
    }
}
