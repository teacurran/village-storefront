package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * LoyaltyTransaction entity representing a ledger entry for points accrual or redemption.
 *
 * <p>
 * Each transaction records a delta (positive for accrual, negative for redemption) and the resulting balance_after
 * snapshot for audit purposes. Transactions are immutable once created (no updates). The expires_at field enables
 * automatic expiration of earned points.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (loyalty_transactions table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>ADR-003: Idempotent redemption operations</li>
 * <li>Task I4.T4: Loyalty ledger with audit trail and expiration</li>
 * </ul>
 */
@Entity
@Table(
        name = "loyalty_transactions",
        indexes = {@Index(
                name = "idx_loyalty_transactions_member",
                columnList = "member_id"),
                @Index(
                        name = "idx_loyalty_transactions_order",
                        columnList = "order_id"),
                @Index(
                        name = "idx_loyalty_transactions_expires",
                        columnList = "expires_at")})
public class LoyaltyTransaction extends PanacheEntityBase {

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

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "member_id",
            nullable = false)
    public LoyaltyMember member;

    /**
     * Associated order ID for purchase-based accrual (null for manual adjustments).
     */
    @Column(
            name = "order_id")
    public UUID orderId;

    /**
     * Points delta: positive for accrual, negative for redemption.
     */
    @Column(
            name = "points_delta",
            nullable = false)
    public Integer pointsDelta;

    /**
     * Member's balance after this transaction (for audit and reconciliation).
     */
    @Column(
            name = "balance_after",
            nullable = false)
    public Integer balanceAfter;

    /**
     * Transaction type: earned, redeemed, expired, adjusted, reversed.
     */
    @Column(
            name = "transaction_type",
            nullable = false,
            length = 50)
    public String transactionType;

    /**
     * Human-readable reason (e.g., "Purchase #12345", "Birthday bonus", "Admin adjustment").
     */
    @Column(
            length = 500)
    public String reason;

    /**
     * Source context: order, admin_adjustment, expiration_job, tier_bonus, etc.
     */
    @Column(
            length = 100)
    public String source;

    /**
     * Expiration timestamp for earned points (null = never expires).
     */
    @Column(
            name = "expires_at")
    public OffsetDateTime expiresAt;

    /**
     * Idempotency key for redemption operations (prevents duplicate debits).
     */
    @Column(
            name = "idempotency_key",
            length = 255)
    public String idempotencyKey;

    /**
     * Additional transaction metadata as JSON (tier at time of transaction, multipliers applied, etc.).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        createdAt = OffsetDateTime.now();
    }
}
