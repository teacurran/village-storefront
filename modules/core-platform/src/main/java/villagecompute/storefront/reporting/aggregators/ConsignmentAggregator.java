package villagecompute.storefront.reporting.aggregators;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.ConsignmentPayoutAggregate;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.repositories.ConsignmentPayoutAggregateRepository;

/**
 * Aggregator for CONSIGNMENT_SOLD and CONSIGNMENT_RETURNED events.
 *
 * <p>
 * Updates consignment_payout_aggregates table with consignor sales and payouts. Uses UPSERT pattern for idempotency.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Table: consignment_payout_aggregates</li>
 * </ul>
 */
@ApplicationScoped
public class ConsignmentAggregator implements EventAggregator {

    private static final Logger LOG = Logger.getLogger(ConsignmentAggregator.class);

    @Inject
    ConsignmentPayoutAggregateRepository aggregateRepository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Override
    @Transactional
    public void processEvent(DomainEvent event) {
        if (!"CONSIGNMENT_SOLD".equals(event.eventType) && !"CONSIGNMENT_RETURNED".equals(event.eventType)) {
            return; // Skip non-consignment events
        }

        try {
            JsonNode payload = objectMapper.readTree(event.payload);

            // Extract consignment details from payload
            UUID consignorId = UUID.fromString(payload.get("consignorId").asText());
            BigDecimal saleAmount = new BigDecimal(payload.get("saleAmount").asText());
            BigDecimal payoutAmount = new BigDecimal(payload.get("payoutAmount").asText());

            // Determine period date (use event occurrence date)
            LocalDate periodDate = event.occurredAt.toLocalDate();

            // Find or create aggregate for this consignor/period
            ConsignmentPayoutAggregate aggregate = aggregateRepository.findByTenantConsignorPeriod(event.tenant.id,
                    consignorId, periodDate, periodDate);

            if (aggregate == null) {
                // Create new aggregate
                aggregate = new ConsignmentPayoutAggregate();
                aggregate.tenant = event.tenant;
                aggregate.consignor = entityManager.getReference(Consignor.class, consignorId);
                aggregate.periodStart = periodDate;
                aggregate.periodEnd = periodDate;
                aggregate.totalOwed = BigDecimal.ZERO;
                aggregate.itemCount = 0;
                aggregate.itemsSold = 0;
                aggregate.jobName = "domain_event_aggregator";
            }

            // Update aggregate based on event type
            if ("CONSIGNMENT_SOLD".equals(event.eventType)) {
                aggregate.totalOwed = aggregate.totalOwed.add(payoutAmount);
                aggregate.itemsSold += 1;
                aggregate.itemCount += 1;
            } else if ("CONSIGNMENT_RETURNED".equals(event.eventType)) {
                // Return decreases owed amount and item count
                aggregate.totalOwed = aggregate.totalOwed.subtract(payoutAmount);
                aggregate.itemsSold -= 1;
            }

            aggregate.dataFreshnessTimestamp = OffsetDateTime.now();
            aggregateRepository.persist(aggregate);

            LOG.debugf("Updated consignment aggregate - tenantId=%s, consignorId=%s, periodDate=%s, eventType=%s",
                    event.tenant.id, consignorId, periodDate, event.eventType);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process consignment event - eventId=%s, eventType=%s", event.id, event.eventType);
            throw new RuntimeException("Consignment aggregation failed", e);
        }
    }
}
