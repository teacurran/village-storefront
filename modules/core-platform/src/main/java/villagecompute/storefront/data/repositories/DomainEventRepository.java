package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.DomainEvent;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for domain events with tenant-scoped queries.
 *
 * <p>
 * Provides access to immutable event records for reporting consumers, audit trails, and event replay scenarios. All
 * queries respect tenant isolation via RLS policies.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Domain event persistence</li>
 * <li>Architecture: Tenant-scoped event queries</li>
 * </ul>
 */
@ApplicationScoped
public class DomainEventRepository implements PanacheRepositoryBase<DomainEvent, UUID> {

    /**
     * Find events by aggregate type and ID within tenant scope.
     *
     * @param aggregateType
     *            type of aggregate (e.g., "INVENTORY_LEVEL")
     * @param aggregateId
     *            UUID of aggregate instance
     * @return list of events ordered by occurrence time
     */
    public List<DomainEvent> findByAggregate(String aggregateType, UUID aggregateId) {
        return find(
                "tenant.id = :tenantId AND aggregateType = :aggregateType AND aggregateId = :aggregateId ORDER BY occurredAt DESC",
                Parameters.with("tenantId", TenantContext.getCurrentTenantId()).and("aggregateType", aggregateType)
                        .and("aggregateId", aggregateId))
                .list();
    }

    /**
     * Find events by event type within tenant scope.
     *
     * @param eventType
     *            type of event (e.g., "INVENTORY_ADJUSTED")
     * @return list of events ordered by occurrence time
     */
    public List<DomainEvent> findByEventType(String eventType) {
        return find("tenant.id = :tenantId AND eventType = :eventType ORDER BY occurredAt DESC",
                Parameters.with("tenantId", TenantContext.getCurrentTenantId()).and("eventType", eventType)).list();
    }

    /**
     * Find events by event type within a time range.
     *
     * @param eventType
     *            type of event
     * @param since
     *            start of time range
     * @param until
     *            end of time range
     * @return list of events ordered by occurrence time
     */
    public List<DomainEvent> findByEventTypeAndTimeRange(String eventType, OffsetDateTime since, OffsetDateTime until) {
        return find(
                "tenant.id = :tenantId AND eventType = :eventType AND occurredAt >= :since AND occurredAt <= :until ORDER BY occurredAt DESC",
                Parameters.with("tenantId", TenantContext.getCurrentTenantId()).and("eventType", eventType)
                        .and("since", since).and("until", until))
                .list();
    }

    /**
     * Find recent events for a tenant.
     *
     * @param limit
     *            maximum number of events to return
     * @return list of most recent events
     */
    public List<DomainEvent> findRecent(int limit) {
        return find("tenant.id = :tenantId ORDER BY occurredAt DESC",
                Parameters.with("tenantId", TenantContext.getCurrentTenantId())).page(0, limit).list();
    }

    /**
     * Count events by type within tenant scope.
     *
     * @param eventType
     *            type of event
     * @return count of events
     */
    public long countByEventType(String eventType) {
        return count("tenant.id = :tenantId AND eventType = :eventType",
                Parameters.with("tenantId", TenantContext.getCurrentTenantId()).and("eventType", eventType));
    }
}
