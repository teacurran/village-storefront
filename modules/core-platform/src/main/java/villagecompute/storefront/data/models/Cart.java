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
 * Cart entity representing a shopping cart for authenticated users or guest sessions.
 *
 * <p>
 * Carts can belong to either an authenticated user (via user_id) or a guest session (via session_id). The metadata
 * field stores cart-level information as JSONB (discounts, shipping snapshots, feature flags, etc.).
 *
 * <p>
 * Optimistic locking via {@link #version} ensures concurrent cart modifications are handled safely. The expires_at
 * field enables automatic cleanup of abandoned carts.
 *
 * <p>
 * References:
 * <ul>
 * <li>ERD: datamodel_erd.puml (carts table)</li>
 * <li>ADR-001: Multi-tenant data isolation via tenant_id</li>
 * <li>Task I2.T4: Cart domain implementation with optimistic locking</li>
 * </ul>
 */
@Entity
@Table(
        name = "carts")
public class Cart extends PanacheEntityBase {

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
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id")
    public User user; // Nullable for guest carts

    @Column(
            name = "session_id",
            length = 255)
    public String sessionId; // For guest cart tracking

    @JdbcTypeCode(SqlTypes.JSON)
    public String metadata; // Cart-level data (discounts, notes, feature flags snapshot)

    @Column(
            name = "expires_at",
            nullable = false)
    public OffsetDateTime expiresAt;

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

        // Set default expiration to 30 days from now if not set
        if (expiresAt == null) {
            expiresAt = now.plusDays(30);
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
