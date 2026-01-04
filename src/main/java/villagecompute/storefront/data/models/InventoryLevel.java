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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * InventoryLevel entity tracking stock quantity for product variants across locations.
 *
 * <p>
 * Each variant can have inventory tracked at multiple locations (warehouses, stores, suppliers). The quantity field
 * represents available stock, while reserved tracks items in pending orders.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (inventory_levels table)</li>
 * <li>ADR-001: Tenant-scoped inventory tracking</li>
 * </ul>
 */
@Entity
@Table(
        name = "inventory_levels",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_inventory_levels_tenant_variant_location",
                columnNames = {"tenant_id", "variant_id", "location"})})
public class InventoryLevel extends PanacheEntityBase {

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
            name = "variant_id",
            nullable = false)
    public ProductVariant variant;

    @Column(
            nullable = false,
            length = 100)
    public String location; // e.g., "warehouse-1", "store-main", "supplier-a"

    @Column(
            nullable = false)
    public Integer quantity = 0;

    @Column(
            nullable = false)
    public Integer reserved = 0;

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

    /**
     * Calculate available quantity (total - reserved).
     *
     * @return available quantity for sale
     */
    public int getAvailableQuantity() {
        return Math.max(0, quantity - reserved);
    }
}
