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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * PayoutLedger entity representing the balance sheet for a consignor.
 *
 * <p>
 * Tracks pending and available balances for each consignor. Pending amounts represent unsettled sales awaiting the
 * payout period threshold, while available amounts can be withdrawn immediately.
 *
 * <p>
 * This entity enforces a unique constraint per tenant-consignor combination to ensure single ledger ownership.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (payout_ledger table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I3.T5: Consignment payout ledger implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "payout_ledger",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_payout_ledger_tenant_consignor",
                columnNames = {"tenant_id", "consignor_id"}))
public class PayoutLedger extends PanacheEntityBase {

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
            name = "consignor_id",
            nullable = false)
    @OnDelete(
            action = OnDeleteAction.CASCADE)
    public Consignor consignor;

    /**
     * Pending balance from sales not yet settled. Stored as NUMERIC(19,4) per Money schema standards.
     */
    @Column(
            name = "pending_balance",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal pendingBalance = BigDecimal.ZERO;

    /**
     * Available balance ready for payout. Stored as NUMERIC(19,4) per Money schema standards.
     */
    @Column(
            name = "available_balance",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal availableBalance = BigDecimal.ZERO;

    @Column(
            nullable = false,
            length = 3)
    public String currency = "USD";

    /**
     * Timestamp of last balance update for audit tracking.
     */
    @Column(
            name = "last_updated_at",
            nullable = false)
    public OffsetDateTime lastUpdatedAt;

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
        lastUpdatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        OffsetDateTime now = OffsetDateTime.now();
        updatedAt = now;
        lastUpdatedAt = now;
    }
}
