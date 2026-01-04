package villagecompute.storefront.pos.offline;

import java.time.OffsetDateTime;
import java.util.UUID;

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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;

/**
 * POS Device entity representing registered point-of-sale terminals with offline capabilities.
 *
 * <p>
 * Each device can queue transactions offline and sync them when connectivity is restored. Devices maintain encryption
 * keys for securing offline payloads and can be paired with Stripe Terminal readers.
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260110__pos_offline_queue.sql</li>
 * <li>Architecture: §3.19.10 POS and Offline Processor</li>
 * <li>Task I4.T7: POS offline queue + Stripe Terminal integration</li>
 * </ul>
 */
@Entity
@Table(
        name = "pos_devices")
public class POSDevice extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_TENANT_AND_IDENTIFIER = "tenant.id = :tenantId AND deviceIdentifier = :deviceIdentifier";
    public static final String QUERY_FIND_ACTIVE_BY_TENANT = "tenant.id = :tenantId AND status = :status";

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

    /**
     * Hardware identifier (MAC address, serial number, or unique device ID).
     */
    @Column(
            name = "device_identifier",
            nullable = false,
            length = 255)
    public String deviceIdentifier;

    /**
     * User-friendly device name for staff identification.
     */
    @Column(
            name = "device_name",
            nullable = false,
            length = 255)
    public String deviceName;

    /**
     * Physical location name (e.g., "Main Store Front Counter", "Back Office").
     */
    @Column(
            name = "location_name",
            length = 255)
    public String locationName;

    /**
     * Hardware model (e.g., "iPad Pro 12.9", "BBPOS WisePad 3").
     */
    @Column(
            name = "hardware_model",
            length = 100)
    public String hardwareModel;

    /**
     * Firmware version for tracking updates.
     */
    @Column(
            name = "firmware_version",
            length = 50)
    public String firmwareVersion;

    /**
     * Stripe Terminal reader ID if paired with hardware reader.
     */
    @Column(
            name = "stripe_terminal_id",
            length = 255)
    public String stripeTerminalId;

    /**
     * SHA-256 hash of device encryption key (never store raw key in DB).
     */
    @Column(
            name = "encryption_key_hash",
            nullable = false,
            length = 128)
    public String encryptionKeyHash;

    /**
     * Encryption key version for rotation support.
     */
    @Column(
            name = "encryption_key_version",
            nullable = false)
    public Integer encryptionKeyVersion = 1;

    /**
     * Short pairing code for initial device registration.
     */
    @Column(
            name = "pairing_code",
            length = 12)
    public String pairingCode;

    /**
     * When pairing code expires (typically 15 minutes after generation).
     */
    @Column(
            name = "pairing_expires_at")
    public OffsetDateTime pairingExpiresAt;

    /**
     * Device status: pending (awaiting pairing), active, suspended, retired.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 20)
    public DeviceStatus status = DeviceStatus.PENDING;

    /**
     * Last time device checked in with server (heartbeat).
     */
    @Column(
            name = "last_seen_at")
    public OffsetDateTime lastSeenAt;

    /**
     * Last successful sync completion timestamp.
     */
    @Column(
            name = "last_synced_at")
    public OffsetDateTime lastSyncedAt;

    /**
     * Audit: when device was created.
     */
    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    /**
     * Audit: when device was last updated.
     */
    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    /**
     * User who created the device registration.
     */
    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by")
    @OnDelete(
            action = OnDeleteAction.SET_NULL)
    public User createdBy;

    /**
     * User who last updated the device.
     */
    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "updated_by")
    @OnDelete(
            action = OnDeleteAction.SET_NULL)
    public User updatedBy;

    @PrePersist
    public void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    /**
     * Find device by tenant and hardware identifier.
     */
    public static POSDevice findByTenantAndIdentifier(UUID tenantId, String deviceIdentifier) {
        return find(QUERY_FIND_BY_TENANT_AND_IDENTIFIER,
                Parameters.with("tenantId", tenantId).and("deviceIdentifier", deviceIdentifier)).firstResult();
    }

    /**
     * Find all active devices for current tenant.
     */
    public static java.util.List<POSDevice> findActiveByTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_ACTIVE_BY_TENANT,
                Parameters.with("tenantId", tenantId).and("status", DeviceStatus.ACTIVE)).list();
    }

    /**
     * Check if pairing code is still valid.
     */
    public boolean isPairingCodeValid() {
        return pairingCode != null && pairingExpiresAt != null && OffsetDateTime.now().isBefore(pairingExpiresAt);
    }

    /**
     * Device lifecycle status.
     */
    public enum DeviceStatus {
        PENDING, // Awaiting pairing completion
        ACTIVE, // Paired and operational
        SUSPENDED, // Temporarily disabled
        RETIRED // Decommissioned
    }
}
