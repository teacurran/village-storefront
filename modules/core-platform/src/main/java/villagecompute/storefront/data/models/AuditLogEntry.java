package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Cross-cutting audit log entry. Records tenant-scoped actions for compliance and reporting.
 *
 * <p>
 * The audit table backs admin reporting, alerting, and downstream reconciliations. Entries are append-only.
 * </p>
 */
@Entity
@Table(
        name = "audit_log_entries")
public class AuditLogEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            name = "tenant_id")
    public UUID tenantId;

    @Column(
            name = "user_id")
    public UUID userId;

    @Column(
            nullable = false,
            length = 100)
    public String action;

    @Column(
            name = "entity_type",
            length = 100)
    public String entityType;

    @Column(
            name = "entity_id")
    public UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String changes;

    @Column(
            name = "ip_address",
            length = 255)
    public String ipAddress;

    @Column(
            name = "user_agent",
            columnDefinition = "TEXT")
    public String userAgent;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (tenantId == null && TenantContext.hasContext()) {
            tenantId = TenantContext.getCurrentTenantId();
        }
    }
}
