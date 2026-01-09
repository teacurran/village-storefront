package villagecompute.storefront.services;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.data.repositories.DomainEventRepository;
import villagecompute.storefront.tenant.TenantContext;

/**
 * Service for publishing domain events to the event store.
 *
 * <p>
 * Handles serialization of event payloads to JSON, metadata enrichment with correlation context, and persistence to the
 * domain_events table. All events are tenant-scoped and immutable once persisted.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Domain event publishing service</li>
 * <li>Architecture: Event-driven reporting projections</li>
 * </ul>
 */
@ApplicationScoped
public class DomainEventPublisher {

    private static final Logger LOG = Logger.getLogger(DomainEventPublisher.class);

    @Inject
    DomainEventRepository eventRepository;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Publish a domain event with typed payload.
     *
     * @param aggregateType
     *            type of aggregate (e.g., "INVENTORY_LEVEL", "INVENTORY_TRANSFER")
     * @param aggregateId
     *            UUID of aggregate instance
     * @param eventType
     *            type of event (e.g., "INVENTORY_ADJUSTED", "TRANSFER_INITIATED")
     * @param payload
     *            event payload object (will be serialized to JSON)
     * @return persisted domain event
     */
    @Transactional
    public DomainEvent publish(String aggregateType, UUID aggregateId, String eventType, Object payload) {
        return publish(aggregateType, aggregateId, eventType, payload, null);
    }

    /**
     * Publish a domain event with typed payload and custom metadata.
     *
     * @param aggregateType
     *            type of aggregate
     * @param aggregateId
     *            UUID of aggregate instance
     * @param eventType
     *            type of event
     * @param payload
     *            event payload object (will be serialized to JSON)
     * @param customMetadata
     *            additional metadata to include (merged with default metadata)
     * @return persisted domain event
     */
    @Transactional
    public DomainEvent publish(String aggregateType, UUID aggregateId, String eventType, Object payload,
            Map<String, Object> customMetadata) {

        UUID tenantId = TenantContext.getCurrentTenantId();

        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }
        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }

        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String metadataJson = buildMetadataJson(customMetadata);

            DomainEvent event = new DomainEvent();
            event.aggregateType = aggregateType;
            event.aggregateId = aggregateId;
            event.eventType = eventType;
            event.payload = payloadJson;
            event.metadata = metadataJson;
            event.occurredAt = OffsetDateTime.now();

            eventRepository.persist(event);

            LOG.infof(
                    "Published domain event - tenantId=%s, eventType=%s, aggregateType=%s, aggregateId=%s, eventId=%s",
                    tenantId, eventType, aggregateType, aggregateId, event.id);

            return event;

        } catch (JsonProcessingException e) {
            LOG.errorf(e, "Failed to serialize event payload - tenantId=%s, eventType=%s, aggregateId=%s", tenantId,
                    eventType, aggregateId);
            throw new IllegalArgumentException("Failed to serialize event payload", e);
        }
    }

    /**
     * Build metadata JSON including tenant context, correlation IDs, and custom fields.
     *
     * @param customMetadata
     *            optional custom metadata to merge
     * @return JSON string
     * @throws JsonProcessingException
     *             if serialization fails
     */
    private String buildMetadataJson(Map<String, Object> customMetadata) throws JsonProcessingException {
        Map<String, Object> metadata = new HashMap<>();

        // Add tenant context
        UUID tenantId = TenantContext.getCurrentTenantId();
        metadata.put("tenantId", tenantId.toString());

        // Add timestamp
        metadata.put("publishedAt", OffsetDateTime.now().toString());

        // Merge custom metadata if provided
        if (customMetadata != null) {
            metadata.putAll(customMetadata);
        }

        return objectMapper.writeValueAsString(metadata);
    }
}
