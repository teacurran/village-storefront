package villagecompute.storefront.platformops.data.models;

import java.net.InetAddress;
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

import villagecompute.storefront.data.models.Tenant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Impersonation session entity. Tracks platform admin impersonation of tenant users.
 *
 * <p>
 * Each impersonation session creates an audit trail with start/end timestamps, reason, and optional ticket reference.
 * Active sessions have {@code endedAt == null}.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T2: Platform admin console</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.8.1 (Impersonation)</li>
 * <li>Rationale: 05_Rationale_and_Future.md Section 4.3.7 (Impersonation Abuse Mitigation)</li>
 * <li>Migration: V20260111__platform_admin_tables.sql</li>
 * </ul>
 *
 * @see PlatformCommand
 */
@Entity
@Table(
        name = "impersonation_sessions")
public class ImpersonationSession extends PanacheEntityBase {

    public static final String QUERY_FIND_ACTIVE_BY_ADMIN = "ImpersonationSession.findActiveByAdmin";
    public static final String QUERY_FIND_BY_TARGET_TENANT = "ImpersonationSession.findByTargetTenant";
    public static final String QUERY_COUNT_ACTIVE_SESSIONS = "ImpersonationSession.countActiveSessions";

    @Id
    @GeneratedValue
    public UUID id;

    @Column(
            name = "platform_admin_id",
            nullable = false)
    public UUID platformAdminId;

    @Column(
            name = "platform_admin_email",
            nullable = false,
            length = 255)
    public String platformAdminEmail;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "target_tenant_id",
            nullable = false)
    public Tenant targetTenant;

    @Column(
            name = "target_user_id")
    public UUID targetUserId; // Nullable: NULL = tenant admin mode

    @Column(
            name = "target_user_email",
            length = 255)
    public String targetUserEmail;

    @Column(
            columnDefinition = "TEXT",
            nullable = false)
    public String reason; // Required, min 10 chars (enforced by DB constraint)

    @Column(
            name = "ticket_number",
            length = 100)
    public String ticketNumber;

    @Column(
            name = "started_at",
            nullable = false)
    public OffsetDateTime startedAt;

    @Column(
            name = "ended_at")
    public OffsetDateTime endedAt; // NULL = session still active

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "start_command_id")
    public PlatformCommand startCommand;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "end_command_id")
    public PlatformCommand endCommand;

    @Column(
            name = "ip_address",
            nullable = false,
            columnDefinition = "inet")
    public InetAddress ipAddress;

    @Column(
            name = "user_agent",
            columnDefinition = "TEXT")
    public String userAgent;

    @PrePersist
    public void prePersist() {
        if (startedAt == null) {
            startedAt = OffsetDateTime.now();
        }
    }

    /**
     * Check if this impersonation session is currently active.
     *
     * @return true if session is active (not ended)
     */
    public boolean isActive() {
        return endedAt == null;
    }

    /**
     * End this impersonation session.
     *
     * @param endCommand
     *            platform command recording the session termination
     */
    public void end(PlatformCommand endCommand) {
        this.endedAt = OffsetDateTime.now();
        this.endCommand = endCommand;
    }
}
