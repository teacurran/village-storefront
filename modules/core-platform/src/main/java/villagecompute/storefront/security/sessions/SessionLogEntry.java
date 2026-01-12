package villagecompute.storefront.security.sessions;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import villagecompute.storefront.data.models.Tenant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Immutable record of authentication sessions captured for auditing and privacy tooling.
 *
 * <p>
 * Backed by the {@code session_log} partitioned table (90-day hot storage). Older partitions are archived but still
 * materialized via this entity when hydrated for privacy exports.
 */
@Entity
@Table(
        name = "session_log")
public class SessionLogEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @Column(
            name = "user_type",
            nullable = false,
            length = 20)
    public String userType;

    @Column(
            name = "user_id")
    public UUID userId;

    @Column(
            name = "ip")
    public String ipAddress;

    @Column(
            name = "user_agent")
    public String userAgent;

    @Column(
            name = "device_fingerprint")
    public String deviceFingerprint;

    @Column(
            name = "login_at",
            nullable = false)
    public OffsetDateTime loginAt;

    @Column(
            name = "last_activity_at")
    public OffsetDateTime lastActivityAt;

    @Column(
            name = "logout_reason")
    public String logoutReason;

    @Column(
            name = "impersonation_context",
            columnDefinition = "jsonb")
    public String impersonationContext;

    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;
}
