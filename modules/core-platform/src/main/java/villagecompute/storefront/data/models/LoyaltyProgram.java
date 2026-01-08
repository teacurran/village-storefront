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

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * LoyaltyProgram entity representing a tenant's loyalty program configuration.
 *
 * <p>
 * Defines the rules for points accrual, redemption rates, tier thresholds, and expiration policies. Each tenant has one
 * active loyalty program at a time. Configuration is stored as JSONB to support flexible tier definitions and accrual
 * rules without schema changes.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (loyalty_programs table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I4.T4: Loyalty program configuration with tier and accrual rules</li>
 * </ul>
 */
@Entity
@Table(
        name = "loyalty_programs")
public class LoyaltyProgram extends PanacheEntityBase {

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

    @Column(
            length = 1000)
    public String description;

    @Column(
            nullable = false)
    public Boolean enabled = true;

    /**
     * Points earned per dollar spent (e.g., 1.0 = 1 point per dollar).
     */
    @Column(
            name = "points_per_dollar",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal pointsPerDollar = BigDecimal.ONE;

    /**
     * Dollar value per point when redeeming (e.g., 0.01 = 1 point = $0.01).
     */
    @Column(
            name = "redemption_value_per_point",
            nullable = false,
            precision = 19,
            scale = 4)
    public BigDecimal redemptionValuePerPoint = new BigDecimal("0.01");

    /**
     * Minimum points required to redeem.
     */
    @Column(
            name = "min_redemption_points",
            nullable = false)
    public Integer minRedemptionPoints = 100;

    /**
     * Maximum points that can be redeemed in a single transaction.
     */
    @Column(
            name = "max_redemption_points")
    public Integer maxRedemptionPoints;

    /**
     * Number of days until points expire (null = never expire).
     */
    @Column(
            name = "points_expiration_days")
    public Integer pointsExpirationDays;

    /**
     * Tier configuration as JSON: [{"name": "Bronze", "minPoints": 0, "multiplier": 1.0}, {"name": "Silver",
     * "minPoints": 1000, "multiplier": 1.5}].
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "tier_config")
    public String tierConfig;

    /**
     * Additional program rules as JSON (e.g., bonus multipliers, excluded categories, promotional periods).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata;

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
