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

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * PayoutLineItem entity representing an individual line item in a payout batch.
 *
 * <p>
 * Each line item represents one sold consignment item, including the item subtotal, commission deduction, and net
 * payout amount to the consignor.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (payout_line_items table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "payout_line_items")
public class PayoutLineItem extends PanacheEntityBase {

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
            name = "batch_id",
            nullable = false)
    public PayoutBatch batch;

    /**
     * Reference to order line item that was sold. TODO: Convert to @ManyToOne relationship when OrderLineItem entity is
     * implemented (I2.T3).
     */
    @Column(
            name = "order_line_item_id",
            nullable = false)
    public UUID orderLineItemId;

    /**
     * Subtotal for this line item (before commission). Stored as NUMERIC(19,4) per Money schema standards.
     */
    @Column(
            name = "item_subtotal",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal itemSubtotal;

    /**
     * Commission amount deducted (store's share). Stored as NUMERIC(19,4) per Money schema standards.
     */
    @Column(
            name = "commission_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal commissionAmount;

    /**
     * Net payout to consignor (itemSubtotal - commissionAmount). Stored as NUMERIC(19,4) per Money schema standards.
     */
    @Column(
            name = "net_payout",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal netPayout;

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
            UUID tenantId = TenantContext.getCurrentTenantId();
            tenant = Tenant.findById(tenantId);
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
}
