package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * PayoutBatch entity representing a batch payout to a consignor.
 *
 * <p>
 * Aggregates consignment sales for a specific period and tracks the payout amount and processing status. Links to
 * Stripe Connect payouts via payment_reference.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (payout_batches table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>ADR-003: Checkout saga and payment processing</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "payout_batches")
public class PayoutBatch extends PanacheEntityBase {

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
            name = "consignor_id",
            nullable = false)
    public Consignor consignor;

    /**
     * Start date of the payout period (inclusive).
     */
    @Column(
            name = "period_start",
            nullable = false)
    public LocalDate periodStart;

    /**
     * End date of the payout period (inclusive).
     */
    @Column(
            name = "period_end",
            nullable = false)
    public LocalDate periodEnd;

    /**
     * Total payout amount in the batch currency. Stored as NUMERIC(19,4) per Money schema standards.
     */
    @Column(
            name = "total_amount",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal totalAmount;

    @Column(
            nullable = false,
            length = 3)
    public String currency = "USD";

    @Column(
            nullable = false,
            length = 20)
    public String status = "pending"; // pending|processing|completed|failed

    /**
     * Timestamp when the payout was successfully processed.
     */
    @Column(
            name = "processed_at")
    public OffsetDateTime processedAt;

    /**
     * Reference to external payment provider transaction (e.g., Stripe payout ID).
     */
    @Column(
            name = "payment_reference",
            length = 255)
    public String paymentReference;

    @Version
    @Column(
            name = "version")
    public Long version;

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

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
