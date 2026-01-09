package villagecompute.storefront.data.models;

import java.math.BigDecimal;
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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * PayoutLedgerEntry entity representing a transaction in the payout ledger.
 *
 * <p>
 * Captures all balance-changing events for a consignor including sales, refunds, payouts, and adjustments. Each entry
 * records the amount, type, and resulting balances for full audit trail.
 *
 * <p>
 * Entry types:
 * <ul>
 * <li>SALE: Adds to pending balance when consignment item sold</li>
 * <li>REFUND: Deducts from pending/available balance on order refund</li>
 * <li>SETTLEMENT: Moves pending to available after hold period</li>
 * <li>PAYOUT: Deducts from available balance when payout processed</li>
 * <li>ADJUSTMENT: Manual balance correction (admin action)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (payout_ledger_entries table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I3.T5: Consignment payout ledger implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "payout_ledger_entries")
public class PayoutLedgerEntry extends PanacheEntityBase {

    @Id
    @GeneratedValue
    public UUID id;

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
            name = "ledger_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public PayoutLedger ledger;

    /**
     * Type of ledger transaction. Possible values: SALE, REFUND, SETTLEMENT, PAYOUT, ADJUSTMENT.
     */
    @Column(
            name = "entry_type",
            nullable = false,
            length = 20)
    public String entryType;

    /**
     * Amount of this transaction. Stored as NUMERIC(19,4) per Money schema standards. Positive values add to balance,
     * negative values deduct.
     */
    @Column(
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal amount;

    @Column(
            nullable = false,
            length = 3)
    public String currency = "USD";

    /**
     * Description or memo for this entry (e.g., "Sale of item #123", "Payout batch #456").
     */
    @Column(
            length = 500)
    public String description;

    /**
     * Reference to related entity (e.g., order_id, payout_batch_id, consignment_item_id).
     */
    @Column(
            name = "reference_id")
    public UUID referenceId;

    /**
     * Reference type identifier (e.g., "ORDER", "PAYOUT_BATCH", "CONSIGNMENT_ITEM").
     */
    @Column(
            name = "reference_type",
            length = 50)
    public String referenceType;

    /**
     * Snapshot of pending balance after this transaction.
     */
    @Column(
            name = "pending_balance_after",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal pendingBalanceAfter;

    /**
     * Snapshot of available balance after this transaction.
     */
    @Column(
            name = "available_balance_after",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal availableBalanceAfter;

    /**
     * Additional metadata stored as JSONB (e.g., commission details, original amounts).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            columnDefinition = "jsonb")
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
