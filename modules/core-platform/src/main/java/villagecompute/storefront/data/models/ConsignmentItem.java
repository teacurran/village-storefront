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
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * ConsignmentItem entity representing a product item under consignment.
 *
 * <p>
 * Links products to consignors and tracks the commission agreement for each item. When an item is sold, it can be
 * tracked for payout purposes.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (consignment_items table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "consignment_items")
public class ConsignmentItem extends PanacheEntityBase {

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
            name = "product_id",
            nullable = false)
    public Product product;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "consignor_id",
            nullable = false)
    public Consignor consignor;

    /**
     * Commission rate as a percentage (e.g., 15.00 for 15%). Stored as NUMERIC(5,2) to support rates from 0.00 to
     * 100.00.
     */
    @Column(
            name = "commission_rate",
            nullable = false,
            precision = 5,
            scale = 2)
    public BigDecimal commissionRate;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active"; // active|sold|returned|deleted

    /**
     * Timestamp when the item was sold (transitions to 'sold' status).
     */
    @Column(
            name = "sold_at")
    public OffsetDateTime soldAt;

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
