package villagecompute.storefront.reporting.aggregators;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.LoyaltyAggregate;
import villagecompute.storefront.data.repositories.LoyaltyAggregateRepository;

/**
 * Aggregator for LOYALTY_EARNED, LOYALTY_REDEEMED, and LOYALTY_TIER_CHANGED events.
 *
 * <p>
 * Updates loyalty_aggregates table with daily loyalty program metrics. Uses UPSERT pattern for idempotency.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Table: loyalty_aggregates</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyAggregator implements EventAggregator {

    private static final Logger LOG = Logger.getLogger(LoyaltyAggregator.class);

    @Inject
    LoyaltyAggregateRepository aggregateRepository;

    @Inject
    ObjectMapper objectMapper;

    @Override
    @Transactional
    public void processEvent(DomainEvent event) {
        if (!"LOYALTY_EARNED".equals(event.eventType) && !"LOYALTY_REDEEMED".equals(event.eventType)
                && !"LOYALTY_TIER_CHANGED".equals(event.eventType)) {
            return; // Skip non-loyalty events
        }

        try {
            JsonNode payload = objectMapper.readTree(event.payload);

            // Determine period date (use event occurrence date)
            LocalDate periodDate = event.occurredAt.toLocalDate();

            // Find or create aggregate for this period
            LoyaltyAggregate aggregate = aggregateRepository.findByTenantAndPeriod(event.tenant.id, periodDate);

            if (aggregate == null) {
                // Create new aggregate
                aggregate = new LoyaltyAggregate();
                aggregate.tenant = event.tenant;
                aggregate.periodDate = periodDate;
                aggregate.pointsEarned = 0;
                aggregate.pointsRedeemed = 0;
                aggregate.activeMembers = 0;
                aggregate.tierDistribution = "{}";
                aggregate.jobName = "domain_event_aggregator";
            }

            // Update aggregate based on event type
            switch (event.eventType) {
                case "LOYALTY_EARNED":
                    int pointsEarned = payload.get("points").asInt();
                    aggregate.pointsEarned += pointsEarned;
                    break;

                case "LOYALTY_REDEEMED":
                    int pointsRedeemed = payload.get("points").asInt();
                    aggregate.pointsRedeemed += pointsRedeemed;
                    break;

                case "LOYALTY_TIER_CHANGED":
                    // Update tier distribution (simplified - actual implementation would query customer tiers)
                    LOG.debugf("Tier changed event - tenantId=%s, customerId=%s", event.tenant.id,
                            payload.has("customerId") ? payload.get("customerId").asText() : "unknown");
                    break;
            }

            aggregate.dataFreshnessTimestamp = OffsetDateTime.now();
            aggregateRepository.persist(aggregate);

            LOG.debugf("Updated loyalty aggregate - tenantId=%s, periodDate=%s, eventType=%s", event.tenant.id,
                    periodDate, event.eventType);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process loyalty event - eventId=%s, eventType=%s", event.id, event.eventType);
            throw new RuntimeException("Loyalty aggregation failed", e);
        }
    }
}
