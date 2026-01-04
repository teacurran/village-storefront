package villagecompute.storefront.platformops.data.models;

import java.net.InetAddress;
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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Platform command audit log entity. Records all platform-level administrative actions.
 *
 * <p>
 * <strong>Critical:</strong> This table is NOT tenant-scoped. Platform commands operate across all tenants. Actions
 * targeting specific tenants should record target_tenant_id in the metadata JSONB field.
 *
 * <p>
 * All impersonation operations, store management, and platform configuration changes are logged here for security
 * auditing and compliance.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console</li>
 * <li>ADR-004: Consignment payouts (platform_commands pattern)</li>
 * <li>Migration: V20260111__platform_admin_tables.sql</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8</li>
 * </ul>
 *
 * @see ImpersonationSession
 */
@Entity
@Table(
        name = "platform_commands")
public class PlatformCommand extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_ACTOR = "PlatformCommand.findByActor";
    public static final String QUERY_FIND_BY_ACTION = "PlatformCommand.findByAction";
    public static final String QUERY_FIND_BY_TARGET = "PlatformCommand.findByTarget";
    public static final String QUERY_FIND_BY_DATE_RANGE = "PlatformCommand.findByDateRange";
    public static final String QUERY_COUNT_BY_FILTERS = "PlatformCommand.countByFilters";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            name = "actor_type",
            nullable = false,
            length = 50)
    public String actorType; // 'platform_admin', 'system', 'automation'

    @Column(
            name = "actor_id")
    public UUID actorId;

    @Column(
            name = "actor_email",
            length = 255)
    public String actorEmail;

    @Column(
            nullable = false,
            length = 100)
    public String action; // 'impersonate_start', 'suspend_store', etc.

    @Column(
            name = "target_type",
            length = 50)
    public String targetType; // 'tenant', 'user', 'feature_flag', etc.

    @Column(
            name = "target_id")
    public UUID targetId;

    @Column(
            columnDefinition = "TEXT")
    public String reason; // Required for impersonation (min 10 chars enforced by DB constraint)

    @Column(
            name = "ticket_number",
            length = 100)
    public String ticketNumber; // External ticket reference

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "impersonation_context",
            columnDefinition = "jsonb")
    public String impersonationContext; // { session_id, target_user_id, target_tenant_id }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
    public String metadata; // Action-specific context

    @Column(
            name = "ip_address",
            columnDefinition = "inet")
    public InetAddress ipAddress;

    @Column(
            name = "user_agent",
            columnDefinition = "TEXT")
    public String userAgent;

    @Column(
            name = "occurred_at",
            nullable = false)
    public OffsetDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        if (occurredAt == null) {
            occurredAt = OffsetDateTime.now();
        }
    }
}
