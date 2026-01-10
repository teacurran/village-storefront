package villagecompute.storefront.pos.offline;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * POS Activity Log for auditing all device state changes and staff actions.
 *
 * <p>
 * Tracks login/logout, cash drawer events, sync operations, firmware updates, and offline mode transitions. Provides
 * comprehensive audit trail for compliance and troubleshooting.
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260110__pos_offline_queue.sql</li>
 * <li>Architecture: §3.19.10 POS audit requirements</li>
 * <li>Task I4.T7: POS activity logging</li>
 * </ul>
 */
@Entity
@Table(
        name = "pos_activity_log")
public class POSActivityLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Tenant tenant;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "device_id")
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public POSDevice device;

    /**
     * Activity type classification.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "activity_type",
            nullable = false,
            length = 50)
    public ActivityType activityType;

    /**
     * Staff member who triggered the activity.
     */
    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "staff_user_id")
    @OnDelete(
            action = OnDeleteAction.SET_NULL)
    public User staffUser;

    /**
     * Additional activity metadata (JSON).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb")
    public java.util.Map<String, Object> metadata = new java.util.HashMap<>();

    /**
     * When activity occurred.
     */
    @Column(
            name = "occurred_at",
            nullable = false)
    public OffsetDateTime occurredAt;

    @PrePersist
    public void prePersist() {
        this.occurredAt = OffsetDateTime.now();
    }

    /**
     * POS activity types.
     */
    public enum ActivityType {
        LOGIN, LOGOUT, CASH_DRAWER_OPEN, CASH_DRAWER_CLOSE, SYNC_STARTED, SYNC_COMPLETED, SYNC_FAILED, DEVICE_PAIRED, DEVICE_SUSPENDED, FIRMWARE_UPDATE, OFFLINE_MODE_ENTERED, OFFLINE_MODE_EXITED
    }

    /**
     * Create activity log entry.
     */
    public static POSActivityLog log(POSDevice device, ActivityType activityType, User staffUser,
            java.util.Map<String, Object> metadata) {
        POSActivityLog log = new POSActivityLog();
        log.tenant = device.tenant;
        log.device = device;
        log.activityType = activityType;
        log.staffUser = staffUser;
        log.metadata = metadata != null ? metadata : new java.util.HashMap<>();
        log.persist();
        return log;
    }
}
