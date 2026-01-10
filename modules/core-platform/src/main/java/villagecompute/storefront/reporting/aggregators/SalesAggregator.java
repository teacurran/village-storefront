package villagecompute.storefront.reporting.aggregators;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.SalesByPeriodAggregate;
import villagecompute.storefront.data.repositories.SalesByPeriodAggregateRepository;

/**
 * Aggregator for ORDER_PLACED and ORDER_COMPLETED events.
 *
 * <p>
 * Updates sales_by_period_aggregates table with daily sales totals. Uses UPSERT pattern for idempotency.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Table: sales_by_period_aggregates</li>
 * </ul>
 */
@ApplicationScoped
public class SalesAggregator implements EventAggregator {

    private static final Logger LOG = Logger.getLogger(SalesAggregator.class);

    @Inject
    SalesByPeriodAggregateRepository aggregateRepository;

    @Inject
    ObjectMapper objectMapper;

    @Override
    @Transactional
    public void processEvent(DomainEvent event) {
        if (!"ORDER_PLACED".equals(event.eventType) && !"ORDER_COMPLETED".equals(event.eventType)) {
            return; // Skip non-sales events
        }

        try {
            JsonNode payload = objectMapper.readTree(event.payload);

            // Extract order details from payload
            BigDecimal orderTotal = new BigDecimal(payload.get("totalAmount").asText());
            int itemCount = payload.has("itemCount") ? payload.get("itemCount").asInt() : 0;

            // Determine period date (use event occurrence date)
            LocalDate periodDate = event.occurredAt.toLocalDate();

            // Find or create aggregate for this period
            SalesByPeriodAggregate aggregate = aggregateRepository.findByTenantAndPeriod(event.tenant.id, periodDate,
                    periodDate);

            if (aggregate == null) {
                // Create new aggregate
                aggregate = new SalesByPeriodAggregate();
                aggregate.tenant = event.tenant;
                aggregate.periodStart = periodDate;
                aggregate.periodEnd = periodDate;
                aggregate.totalAmount = BigDecimal.ZERO;
                aggregate.itemCount = 0;
                aggregate.orderCount = 0;
                aggregate.jobName = "domain_event_aggregator";
            }

            // Update aggregate (idempotent UPSERT)
            aggregate.totalAmount = aggregate.totalAmount.add(orderTotal);
            aggregate.itemCount += itemCount;
            aggregate.orderCount += 1;
            aggregate.dataFreshnessTimestamp = OffsetDateTime.now();

            aggregateRepository.persist(aggregate);

            LOG.debugf("Updated sales aggregate - tenantId=%s, periodDate=%s, orderTotal=%s", event.tenant.id,
                    periodDate, orderTotal);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process sales event - eventId=%s, eventType=%s", event.id, event.eventType);
            throw new RuntimeException("Sales aggregation failed", e);
        }
    }
}
