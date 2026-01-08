package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Platform fee configuration entity for multi-tenant marketplace scenarios. Stores tenant-specific fee schedules for
 * calculating platform revenue on transactions.
 *
 * Supports percentage-based and fixed fee structures.
 */
@Entity
@Table(
        name = "platform_fee_configs",
        indexes = {@Index(
                name = "idx_fee_config_tenant_id",
                columnList = "tenant_id",
                unique = true)})
public class PlatformFeeConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false,
            unique = true)
    public Tenant tenant;

    @Column(
            name = "fee_percentage",
            nullable = false,
            precision = 5,
            scale = 4)
    public BigDecimal feePercentage; // e.g., 0.0500 for 5%

    @Column(
            name = "fixed_fee_amount",
            precision = 19,
            scale = 4)
    public BigDecimal fixedFeeAmount; // Optional fixed fee per transaction

    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency; // Currency for fixed fee

    @Column(
            name = "minimum_fee",
            precision = 19,
            scale = 4)
    public BigDecimal minimumFee; // Minimum platform fee per transaction

    @Column(
            name = "maximum_fee",
            precision = 19,
            scale = 4)
    public BigDecimal maximumFee; // Maximum platform fee per transaction

    @Column(
            name = "active",
            nullable = false)
    public boolean active = true;

    @Column(
            name = "effective_from",
            nullable = false)
    public Instant effectiveFrom;

    @Column(
            name = "effective_to")
    public Instant effectiveTo;

    @Column(
            name = "notes",
            columnDefinition = "TEXT")
    public String notes; // Admin notes about fee configuration

    @Column(
            name = "created_at",
            nullable = false)
    public Instant createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public Instant updatedAt;

    @Version
    @Column(
            name = "version")
    public Long version;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (effectiveFrom == null) {
            effectiveFrom = now;
        }
        updatedAt = now;
        if (tenant == null && TenantContext.hasContext()) {
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Find active platform fee configuration for a tenant.
     *
     * @param tenantId
     *            Tenant identifier
     * @return PlatformFeeConfig or null
     */
    public static PlatformFeeConfig findByTenantId(UUID tenantId) {
        return find(
                "tenant.id = ?1 and active = true and effectiveFrom <= ?2 and (effectiveTo is null or effectiveTo > ?2)",
                tenantId, Instant.now()).firstResult();
    }

    /**
     * Calculate platform fee for a given transaction amount.
     *
     * @param transactionAmount
     *            Transaction amount
     * @return Platform fee amount
     */
    public BigDecimal calculateFee(BigDecimal transactionAmount) {
        BigDecimal percentageFee = transactionAmount.multiply(feePercentage);
        BigDecimal totalFee = percentageFee;

        if (fixedFeeAmount != null) {
            totalFee = totalFee.add(fixedFeeAmount);
        }

        if (minimumFee != null && totalFee.compareTo(minimumFee) < 0) {
            totalFee = minimumFee;
        }

        if (maximumFee != null && totalFee.compareTo(maximumFee) > 0) {
            totalFee = maximumFee;
        }

        return totalFee.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
