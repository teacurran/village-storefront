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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * PaymentTender entity tracks multi-tender payment splits for checkout orders.
 *
 * <p>
 * Supports card, gift card, store credit, and loyalty tender portions. Each entry captures the tender source,
 * associated ledger transaction, and lifecycle state (pending/authorized/captured/refunded).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T6: Multi-tender checkout tracking for gift cards and store credit</li>
 * <li>Migration: V20260109__gift_card_store_credit.sql (payment_tenders table)</li>
 * </ul>
 */
@Entity
@Table(
        name = "payment_tenders")
public class PaymentTender extends PanacheEntityBase {

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
    public Tenant tenant;

    @Column(
            name = "order_id",
            nullable = false)
    public Long orderId;

    @Column(
            name = "tender_type",
            nullable = false,
            length = 20)
    public String tenderType;

    @Column(
            name = "amount",
            nullable = false,
            precision = 12,
            scale = 2)
    public BigDecimal amount;

    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "payment_intent_id")
    public PaymentIntent paymentIntent;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "gift_card_id")
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public GiftCard giftCard;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "store_credit_account_id")
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public StoreCreditAccount storeCreditAccount;

    @Column(
            name = "loyalty_transaction_id")
    public UUID loyaltyTransactionId;

    @Column(
            name = "transaction_id")
    public Long transactionId;

    @Column(
            name = "status",
            nullable = false,
            length = 20)
    public String status = "pending";

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (tenant == null && TenantContext.hasContext()) {
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
