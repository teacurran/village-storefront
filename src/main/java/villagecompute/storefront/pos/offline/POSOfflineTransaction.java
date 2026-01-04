package villagecompute.storefront.pos.offline;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * POS Offline Transaction audit record for successfully synced offline sales.
 *
 * <p>
 * After a queued offline transaction is processed, this record captures the sync result including the created order,
 * payment intent, and timing metadata. Provides an immutable audit trail of offline operations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Migration: V20260110__pos_offline_queue.sql</li>
 * <li>Architecture: §3.19.10 POS and Offline Processor (audit requirements)</li>
 * <li>Task I4.T7: POS offline transaction audit trail</li>
 * </ul>
 */
@Entity
@Table(
        name = "pos_offline_transactions")
public class POSOfflineTransaction extends PanacheEntityBase {

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

    @OneToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "queue_entry_id",
            nullable = false,
            unique = true)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public POSOfflineQueue queueEntry;

    /**
     * Client-side transaction ID (copied from queue for query convenience).
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
     * Client timestamp when transaction occurred offline.
     */
    @Column(
            name = "offline_timestamp",
            nullable = false)
    public OffsetDateTime offlineTimestamp;

    /**
     * Created order ID (if checkout flow created an order).
     */
    @Column(
            name = "order_id")
    public Long orderId;

    /**
     * Stripe payment intent ID from sync.
     */
    @Column(
            name = "payment_intent_id",
            length = 255)
    public String paymentIntentId;

    /**
     * Total transaction amount.
     */
    @Column(
            name = "total_amount",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal totalAmount;

    /**
     * Server timestamp when sync completed.
     */
    @Column(
            name = "synced_at",
            nullable = false)
    public OffsetDateTime syncedAt;

    /**
     * Sync processing duration in milliseconds.
     */
    @Column(
            name = "sync_duration_ms")
    public Integer syncDurationMs;

    /**
     * Record creation timestamp.
     */
    @Column(
            name = "created_at",
            nullable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = OffsetDateTime.now();
    }
}
