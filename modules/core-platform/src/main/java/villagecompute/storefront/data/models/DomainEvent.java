package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Domain event entity for event sourcing and reporting projections.
 *
 * <p>
 * Captures immutable business events such as inventory adjustments, transfers, and order state changes. Events are
 * persisted alongside transactional changes to maintain audit trails and enable downstream reporting consumers.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T4: Domain event publishing for inventory operations</li>
 * <li>Architecture: Event-driven reporting projection service</li>
 * <li>ERD: domain_events table specification</li>
 * </ul>
 */
@Entity
@Table(
        name = "domain_events")
public class DomainEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    /**
     * Type of aggregate this event relates to (e.g., "INVENTORY_LEVEL", "INVENTORY_TRANSFER", "ORDER").
     */
    @Column(
            name = "aggregate_type",
            nullable = false,
            length = 100)
    public String aggregateType;

    /**
     * UUID of the aggregate instance this event relates to.
     */
    @Column(
            name = "aggregate_id",
            nullable = false)
    public UUID aggregateId;

    /**
     * Type of event (e.g., "INVENTORY_ADJUSTED", "TRANSFER_INITIATED", "ORDER_PLACED").
     */
    @Column(
            name = "event_type",
            nullable = false,
            length = 100)
    public String eventType;

    /**
     * JSON payload containing event-specific data. Structure varies by event_type.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "payload",
            nullable = false,
            columnDefinition = "jsonb")
    public String payload;

    /**
     * JSON metadata containing correlation IDs, user context, and trace information.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb")
    public String metadata;

    /**
     * Timestamp when the business event occurred.
     */
    @Column(
            name = "occurred_at",
            nullable = false)
    public OffsetDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}
