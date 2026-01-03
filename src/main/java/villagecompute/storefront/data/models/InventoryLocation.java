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

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Physical or virtual inventory storage location.
 *
 * <p>
 * Represents warehouses, retail stores, supplier locations, or other facilities where inventory is tracked. Each
 * location is scoped to a tenant and identified by a unique code.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (inventory_locations table)</li>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>ADR-001: Tenant-scoped inventory tracking</li>
 * </ul>
 */
@Entity
@Table(
        name = "inventory_locations",
        uniqueConstraints = {@UniqueConstraint(
                name = "uk_inventory_locations_tenant_code",
                columnNames = {"tenant_id", "code"})})
public class InventoryLocation extends PanacheEntityBase {

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

    /**
     * Unique location code within tenant (e.g., "warehouse-1", "store-main").
     */
    @Column(
            nullable = false,
            length = 100)
    public String code;

    /**
     * Human-readable location name.
     */
    @Column(
            nullable = false,
            length = 255)
    public String name;

    /**
     * Location type (e.g., "warehouse", "retail", "supplier").
     */
    @Column(
            length = 50)
    public String type;

    /**
     * Physical address in JSON format.
     */
    @Column(
            columnDefinition = "jsonb")
    public String address;

    /**
     * Whether this location is active for new transactions.
     */
    @Column(
            nullable = false)
    public Boolean active = true;

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
