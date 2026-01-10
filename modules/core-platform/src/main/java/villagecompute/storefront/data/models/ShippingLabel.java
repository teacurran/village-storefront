package villagecompute.storefront.data.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Shipping label record tracking carrier labels, tracking numbers, and shipment details.
 *
 * <p>
 * Created when a merchant generates a shipping label for an order. Stores label URL, tracking information, and carrier
 * metadata for fulfillment tracking and customer notifications.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T4: Shipping label endpoints and tracking</li>
 * <li>Architecture §4: Carrier API label generation</li>
 * </ul>
 */
@Entity
@Table(
        name = "shipping_labels")
public class ShippingLabel extends PanacheEntityBase {

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
     * Associated order.
     */
    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "order_id",
            nullable = false)
    public Order order;

    /**
     * Carrier code (USPS, UPS, FEDEX).
     */
    @Column(
            name = "carrier_code",
            nullable = false,
            length = 20)
    public String carrierCode;

    /**
     * Service level (GROUND, PRIORITY, NEXT_DAY_AIR, etc.).
     */
    @Column(
            name = "service_level",
            nullable = false,
            length = 50)
    public String serviceLevel;

    /**
     * Tracking number from carrier.
     */
    @Column(
            name = "tracking_number",
            nullable = false,
            length = 100)
    public String trackingNumber;

    /**
     * Label URL (PDF or image).
     */
    @Column(
            name = "label_url",
            nullable = false,
            length = 500)
    public String labelUrl;

    /**
     * Label status.
     */
    @Enumerated(EnumType.STRING)
    @Column(
            name = "status",
            nullable = false,
            length = 30)
    public LabelStatus status = LabelStatus.CREATED;

    /**
     * Shipping cost charged.
     */
    @Column(
            name = "cost",
            nullable = false,
            precision = 10,
            scale = 2)
    public BigDecimal cost;

    /**
     * Currency code (USD).
     */
    @Column(
            name = "currency",
            nullable = false,
            length = 3)
    public String currency = "USD";

    /**
     * Estimated delivery date.
     */
    @Column(
            name = "estimated_delivery")
    public OffsetDateTime estimatedDelivery;

    /**
     * Actual ship date when package was picked up.
     */
    @Column(
            name = "shipped_at")
    public OffsetDateTime shippedAt;

    /**
     * Actual delivery date when package was delivered.
     */
    @Column(
            name = "delivered_at")
    public OffsetDateTime deliveredAt;

    /**
     * Carrier-specific metadata (JSON).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(
            name = "carrier_metadata",
            columnDefinition = "jsonb")
    public String carrierMetadata;

    /**
     * Correlation ID for tracing.
     */
    @Column(
            name = "correlation_id",
            length = 100)
    public String correlationId;

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

    /**
     * Label lifecycle status.
     */
    public enum LabelStatus {
        CREATED, VOIDED, REFUNDED, IN_TRANSIT, DELIVERED, EXCEPTION
    }

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
