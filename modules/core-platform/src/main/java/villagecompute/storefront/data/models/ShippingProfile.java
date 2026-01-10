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
 * Shipping profile defining carrier preferences, service levels, and credentials for a tenant.
 *
 * <p>
 * Merchants configure one or more shipping profiles to manage carrier accounts, default service levels, and rate
 * calculation rules. Profiles can be associated with specific product types or fulfillment locations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T4: Shipping profile management</li>
 * <li>Architecture §4: Integration Adapter Layer carrier credential management</li>
 * </ul>
 */
@Entity
@Table(
        name = "shipping_profiles")
public class ShippingProfile extends PanacheEntityBase {

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
     * Profile name for merchant reference.
     */
    @Column(
            name = "name",
            nullable = false,
            length = 100)
    public String name;

    /**
     * Whether this is the default profile for the tenant.
     */
    @Column(
            name = "is_default",
            nullable = false)
    public boolean isDefault = false;

    /**
     * Enabled carrier codes (comma-separated: USPS,UPS,FEDEX).
     */
    @Column(
            name = "enabled_carriers",
            nullable = false,
            length = 255)
    public String enabledCarriers;

    /**
     * Default origin address for shipping calculations (JSON).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "origin_address",
            nullable = false,
            columnDefinition = "jsonb")
    public String originAddress;

    /**
     * Carrier-specific credentials and settings (JSON, encrypted at rest).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "carrier_credentials",
            columnDefinition = "jsonb")
    public String carrierCredentials;

    /**
     * Metadata for additional configuration (JSON).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "metadata",
            columnDefinition = "jsonb")
    public String metadata;

    /**
     * Whether this profile is active.
     */
    @Column(
            name = "active",
            nullable = false)
    public boolean active = true;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @Version
    @Column(
            name = "version")
    public Long version;

    @PrePersist
    void prePersist() {
        if (tenant == null && TenantContext.getCurrentTenantId() != null) {
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
