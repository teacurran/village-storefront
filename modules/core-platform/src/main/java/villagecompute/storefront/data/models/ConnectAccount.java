package villagecompute.storefront.data.models;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Connected account entity for marketplace providers (Stripe Connect, PayPal Commerce, etc.). Tracks onboarding status,
 * capabilities, and provider-specific account identifiers.
 *
 * Used for consignor/vendor payout routing and split payment scenarios.
 */
@Entity
@Table(
        name = "connect_accounts",
        indexes = {@Index(
                name = "idx_connect_provider_account_id",
                columnList = "provider_account_id",
                unique = true),
                @Index(
                        name = "idx_connect_tenant_id",
                        columnList = "tenant_id"),
                @Index(
                        name = "idx_connect_consignor_id",
                        columnList = "consignor_id")})
public class ConnectAccount extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @Column(
            name = "provider",
            nullable = false,
            length = 50)
    public String provider; // "stripe", "paypal", etc.

    @Column(
            name = "provider_account_id",
            nullable = false,
            unique = true,
            length = 255)
    public String providerAccountId; // Provider's connected account ID

    @Column(
            name = "consignor_id")
    public UUID consignorId; // Link to Consignor entity

    @Column(
            name = "email",
            length = 255)
    public String email;

    @Column(
            name = "business_name",
            length = 255)
    public String businessName;

    @Column(
            name = "country",
            length = 2)
    public String country; // ISO 3166-1 alpha-2 country code

    @Column(
            name = "onboarding_status",
            nullable = false,
            length = 50)
    @Enumerated(EnumType.STRING)
    public OnboardingStatus onboardingStatus;

    @Column(
            name = "onboarding_url",
            length = 1000)
    public String onboardingUrl; // URL for account holder to complete onboarding

    @Column(
            name = "capabilities_enabled",
            columnDefinition = "TEXT")
    public String capabilitiesEnabled; // JSON array of enabled capabilities

    @Column(
            name = "payouts_enabled",
            nullable = false)
    public boolean payoutsEnabled = false;

    @Column(
            name = "charges_enabled",
            nullable = false)
    public boolean chargesEnabled = false;

    @Column(
            name = "metadata",
            columnDefinition = "TEXT")
    public String metadata; // JSON metadata

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
     * Onboarding status enumeration.
     */
    public enum OnboardingStatus {
        PENDING, IN_PROGRESS, COMPLETED, RESTRICTED, DISABLED
    }

    /**
     * Find connected account by provider account ID.
     *
     * @param providerAccountId
     *            Provider's account identifier
     * @return ConnectAccount or null
     */
    public static ConnectAccount findByProviderAccountId(UUID tenantId, String providerAccountId) {
        return find("providerAccountId = ?1 and tenant.id = ?2", providerAccountId, tenantId).firstResult();
    }

    /**
     * Find connected account by consignor ID.
     *
     * @param consignorId
     *            Consignor identifier
     * @return ConnectAccount or null
     */
    public static ConnectAccount findByConsignorId(UUID tenantId, UUID consignorId) {
        return find("consignorId = ?1 and tenant.id = ?2", consignorId, tenantId).firstResult();
    }
}
