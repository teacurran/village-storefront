package villagecompute.storefront.reporting.aggregators;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.models.InventoryAgingAggregate;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.InventoryAgingAggregateRepository;

/**
 * Aggregator for INVENTORY_ADJUSTED, INVENTORY_RECEIVED, and INVENTORY_TRANSFERRED events.
 *
 * <p>
 * Updates inventory_aging_aggregates table with days-in-stock metrics. Uses UPSERT pattern for idempotency.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Reporting & Retention pipeline</li>
 * <li>Table: inventory_aging_aggregates</li>
 * </ul>
 */
@ApplicationScoped
public class InventoryAggregator implements EventAggregator {

    private static final Logger LOG = Logger.getLogger(InventoryAggregator.class);

    @Inject
    InventoryAgingAggregateRepository aggregateRepository;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Override
    @Transactional
    public void processEvent(DomainEvent event) {
        if (!"INVENTORY_ADJUSTED".equals(event.eventType) && !"INVENTORY_RECEIVED".equals(event.eventType)
                && !"INVENTORY_TRANSFERRED".equals(event.eventType)) {
            return; // Skip non-inventory events
        }

        try {
            JsonNode payload = objectMapper.readTree(event.payload);

            // Extract inventory details from payload
            UUID variantId = UUID.fromString(payload.get("variantId").asText());
            UUID locationId = UUID.fromString(payload.get("locationId").asText());
            int quantity = payload.has("quantity") ? payload.get("quantity").asInt() : 0;

            // Find or create aggregate for this variant/location
            InventoryAgingAggregate aggregate = aggregateRepository.findByTenantVariantLocation(event.tenant.id,
                    variantId, locationId);

            if (aggregate == null) {
                // Create new aggregate
                aggregate = new InventoryAgingAggregate();
                aggregate.tenant = event.tenant;
                aggregate.variant = entityManager.getReference(ProductVariant.class, variantId);
                aggregate.location = entityManager.getReference(InventoryLocation.class, locationId);
                aggregate.quantity = 0;
                aggregate.firstReceivedAt = event.occurredAt;
                aggregate.daysInStock = 0;
                aggregate.jobName = "domain_event_aggregator";
            }

            // Update aggregate (idempotent UPSERT)
            aggregate.quantity = quantity; // Overwrite with latest quantity
            aggregate.dataFreshnessTimestamp = OffsetDateTime.now();

            // Calculate days in stock
            if (aggregate.firstReceivedAt != null) {
                long days = Duration.between(aggregate.firstReceivedAt, OffsetDateTime.now()).toDays();
                aggregate.daysInStock = (int) days;
            }

            aggregateRepository.persist(aggregate);

            LOG.debugf("Updated inventory aging aggregate - tenantId=%s, variantId=%s, locationId=%s, quantity=%d",
                    event.tenant.id, variantId, locationId, quantity);

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process inventory event - eventId=%s, eventType=%s", event.id, event.eventType);
            throw new RuntimeException("Inventory aggregation failed", e);
        }
    }
}
