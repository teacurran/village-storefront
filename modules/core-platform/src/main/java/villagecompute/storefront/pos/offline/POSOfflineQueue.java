package villagecompute.storefront.pos.offline;

import java.math.BigDecimal;
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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.services.jobs.config.JobPriority;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;

/**
 * POS Offline Queue entry representing an encrypted offline transaction awaiting sync.
 *
 * <p>
 * When a POS device loses connectivity, transactions are encrypted and queued locally, then uploaded to this table when
 * connection is restored. Background jobs process the queue, decrypt payloads, and replay transactions through the
 * checkout flow.
 *
 * <p>
 * Idempotency keys prevent duplicate charges if jobs retry. The encrypted payload contains cart, payment method, and
 * customer data.
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260110__pos_offline_queue.sql</li>
 * <li>Architecture: §3.6 Background Processing (offline batch uploads)</li>
 * <li>Task I4.T7: POS offline queue + sync job implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "pos_offline_queue")
public class POSOfflineQueue extends PanacheEntityBase {

    public static final String QUERY_FIND_QUEUED_BY_PRIORITY = "syncStatus = :status ORDER BY syncPriority DESC, createdAt ASC";
    public static final String QUERY_FIND_BY_DEVICE_AND_STATUS = "device.id = :deviceId AND syncStatus = :status ORDER BY createdAt ASC";
    public static final String QUERY_COUNT_QUEUED_BY_DEVICE = "device.id = :deviceId AND syncStatus = :status";

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
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "device_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public POSDevice device;

    /**
     * AES-GCM encrypted transaction payload (JSON bytes).
     */
    @Lob
    @Column(
            name = "encrypted_payload",
            nullable = false)
    public byte[] encryptedPayload;

    /**
     * Encryption key version (matches device.encryptionKeyVersion at time of encryption).
     */
    @Column(
            name = "encryption_key_version",
            nullable = false)
    public Integer encryptionKeyVersion;

    /**
     * Initialization vector for AES-GCM decryption.
     */
    @Column(
            name = "encryption_iv",
            nullable = false)
    public byte[] encryptionIv;

    /**
     * Client-side UUID for deduplication.
     */
    @Column(
            name = "local_transaction_id",
            nullable = false,
            length = 255)
    public String localTransactionId;

    /**
     * Staff member who created the offline transaction.
     */
    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "staff_user_id")
    @OnDelete(
            action = OnDeleteAction.SET_NULL)
    public User staffUser;

    /**
     * Client local timestamp when transaction occurred.
     */
    @Column(
            name = "transaction_timestamp",
            nullable = false)
    public OffsetDateTime transactionTimestamp;

    /**
     * Transaction amount (for observability metrics, not authoritative).
     */
    @Column(
            name = "transaction_amount",
            precision = 12,
            scale = 2)
    public BigDecimal transactionAmount;

    /**
     * Sync status: queued (pending), processing, completed, failed.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "sync_status",
            nullable = false,
            length = 20)
    public SyncStatus syncStatus = SyncStatus.QUEUED;

    /**
     * Sync priority (maps to JobPriority for queue processing).
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "sync_priority",
            nullable = false,
            length = 20)
    public SyncPriority syncPriority = SyncPriority.HIGH;

    /**
     * When sync job started processing this entry.
     */
    @Column(
            name = "sync_started_at")
    public OffsetDateTime syncStartedAt;

    /**
     * When sync completed successfully.
     */
    @Column(
            name = "sync_completed_at")
    public OffsetDateTime syncCompletedAt;

    /**
     * Number of sync attempts (for retry tracking).
     */
    @Column(
            name = "sync_attempt_count",
            nullable = false)
    public Integer syncAttemptCount = 0;

    /**
     * Last error message if sync failed.
     */
    @Column(
            name = "last_sync_error",
            columnDefinition = "TEXT")
    public String lastSyncError;

    /**
     * Idempotency key for preventing duplicate charges. Format: {tenantId}:{deviceId}:{localTxId}
     */
    @Column(
            name = "idempotency_key",
            nullable = false,
            unique = true,
            length = 255)
    public String idempotencyKey;

    /**
     * When queue entry was created.
     */
    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    /**
     * When queue entry was last updated.
     */
    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

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
     * Find next batch of queued entries ordered by priority and creation time.
     */
    public static java.util.List<POSOfflineQueue> findQueuedByPriority(int limit) {
        return find(QUERY_FIND_QUEUED_BY_PRIORITY, Parameters.with("status", SyncStatus.QUEUED)).page(0, limit).list();
    }

    /**
     * Find all queue entries for a device with specific status.
     */
    public static java.util.List<POSOfflineQueue> findByDeviceAndStatus(Long deviceId, SyncStatus status) {
        return find(QUERY_FIND_BY_DEVICE_AND_STATUS, Parameters.with("deviceId", deviceId).and("status", status))
                .list();
    }

    /**
     * Count queued transactions for a device.
     */
    public static long countQueuedByDevice(Long deviceId) {
        return count(QUERY_COUNT_QUEUED_BY_DEVICE,
                Parameters.with("deviceId", deviceId).and("status", SyncStatus.QUEUED));
    }

    /**
     * Generate idempotency key from tenant, device, and transaction ID.
     */
    public static String generateIdempotencyKey(UUID tenantId, Long deviceId, String localTxId) {
        return String.format("%s:%d:%s", tenantId, deviceId, localTxId);
    }

    /**
     * Sync status lifecycle.
     */
    public enum SyncStatus {
        QUEUED, // Awaiting processing
        PROCESSING, // Currently being synced
        COMPLETED, // Successfully replayed
        FAILED // Exhausted retries or unrecoverable error
    }

    /**
     * Sync priority levels (map to JobPriority for consistency).
     */
    public enum SyncPriority {
        CRITICAL, // Payment failures, urgent transactions
        HIGH, // Standard offline transactions
        DEFAULT // Bulk or low-priority sync
    }

    /**
     * Convert SyncPriority to JobPriority for job processor.
     */
    public JobPriority toJobPriority() {
        return switch (this.syncPriority) {
            case CRITICAL -> JobPriority.CRITICAL;
            case HIGH -> JobPriority.HIGH;
            case DEFAULT -> JobPriority.DEFAULT;
        };
    }
}
