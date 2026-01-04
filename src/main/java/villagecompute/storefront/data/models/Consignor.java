package villagecompute.storefront.data.models;

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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Consignor entity representing a vendor who provides consignment inventory.
 *
 * <p>
 * Consignors are vendors who place items in the store for sale on consignment. The store handles sales and pays the
 * consignor their share based on commission agreements.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (consignors table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I3.T1: Consignment domain implementation</li>
 * </ul>
 */
@Entity
@Table(
        name = "consignors")
public class Consignor extends PanacheEntityBase {

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

    @Column(
            nullable = false,
            length = 255)
    public String name;

    /**
     * Contact information stored as JSONB. Expected fields: email, phone, address, etc.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "contact_info",
            columnDefinition = "jsonb")
    public String contactInfo;

    /**
     * Payout settings stored as JSONB. Expected fields: default_commission_rate, payment_method, tax_info (encrypted),
     * etc.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "payout_settings",
            columnDefinition = "jsonb")
    public String payoutSettings;

    @Column(
            nullable = false,
            length = 20)
    public String status = "active"; // active|suspended|deleted

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
