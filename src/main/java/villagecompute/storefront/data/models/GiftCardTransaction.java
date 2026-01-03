package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.panache.common.Parameters;

/**
 * GiftCardTransaction entity representing ledger entries for gift card balance changes.
 *
 * <p>
 * Immutable audit log of all gift card operations. Supports idempotency via idempotency_key for redemption operations
 * to prevent duplicate charges during checkout retries.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (gift_card_transactions table)</li>
 * <li>ADR-003: Idempotent redemption operations</li>
 * <li>Task I4.T6: Gift card ledger with checkout/POS integration</li>
 * </ul>
 */
@Entity
@Table(
        name = "gift_card_transactions")
public class GiftCardTransaction extends PanacheEntityBase {

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
            name = "gift_card_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public GiftCard giftCard;

    /**
     * Order associated with this transaction (if redemption).
     */
    @Column(
            name = "order_id")
    public Long orderId;

    /**
     * Amount of transaction. Positive for load/refund, negative for redemption.
     */
    @Column(
            name = "amount",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal amount;

    /**
     * Transaction type: issued, redeemed, refunded, adjusted, expired.
     */
    @Column(
            name = "transaction_type",
            nullable = false,
            length = 20)
    public String transactionType;

    /**
     * Balance after this transaction was applied.
     */
    @Column(
            name = "balance_after",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal balanceAfter;

    /**
     * Idempotency key to prevent duplicate redemptions.
     */
    @Column(
            name = "idempotency_key",
            length = 255,
            unique = true)
    public String idempotencyKey;

    /**
     * Human-readable reason for transaction.
     */
    @Column(
            name = "reason",
            columnDefinition = "TEXT")
    public String reason;

    /**
     * Additional metadata as JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata;

    /**
     * POS device ID if redeemed offline.
     */
    @Column(
            name = "pos_device_id")
    public Long posDeviceId;

    /**
     * Timestamp when offline transaction was synced to server.
     */
    @Column(
            name = "offline_synced_at")
    public OffsetDateTime offlineSyncedAt;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "created_by")
    public User createdBy;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    // ========================================
    // Named Queries
    // ========================================

    public static final String QUERY_FIND_BY_IDEMPOTENCY_KEY = "GiftCardTransaction.findByIdempotencyKey";
    public static final String QUERY_FIND_BY_GIFT_CARD = "GiftCardTransaction.findByGiftCard";

    /**
     * Find transaction by idempotency key (tenant-scoped).
     *
     * @param idempotencyKey
     *            idempotency key
     * @return transaction if exists
     */
    public static GiftCardTransaction findByIdempotencyKey(String idempotencyKey) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("idempotencyKey = :key and tenant.id = :tenantId",
                Parameters.with("key", idempotencyKey).and("tenantId", tenantId)).firstResult();
    }

    /**
     * Find transactions for a gift card ordered by creation date DESC.
     *
     * @param giftCardId
     *            gift card ID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of transactions
     */
    public static java.util.List<GiftCardTransaction> findByGiftCard(Long giftCardId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("giftCard.id = :giftCardId and tenant.id = :tenantId order by createdAt desc",
                Parameters.with("giftCardId", giftCardId).and("tenantId", tenantId)).page(page, size).list();
    }
}
