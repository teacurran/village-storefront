package villagecompute.storefront.compliance.data.models;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Marketing consent tracking for compliance with GDPR/CCPA/CAN-SPAM requirements.
 *
 * <p>
 * Records customer consent for marketing communications by channel. Consent timeline exported in privacy exports to
 * satisfy compliance audits.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (consent management)</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 5 (consent tracking)</li>
 * </ul>
 */
@Entity
@Table(
        name = "marketing_consents")
public class MarketingConsent extends PanacheEntityBase {

    public static final String QUERY_FIND_BY_CUSTOMER = "MarketingConsent.findByCustomer";
    public static final String QUERY_FIND_ACTIVE_BY_CHANNEL = "MarketingConsent.findActiveByChannel";
    public static final String QUERY_FIND_BY_TENANT = "MarketingConsent.findByTenant";

    @Id
    @GeneratedValue
    public UUID id;

    @ManyToOne(
            optional = false)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @ManyToOne(
            optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false)
    public User user;

    @Column(
            nullable = false,
            length = 50)
    public String channel; // 'email', 'sms', 'push', 'phone'

    @Column(
            nullable = false)
    public boolean consented;

    @Column(
            name = "consent_source",
            nullable = false,
            length = 100)
    public String consentSource; // 'web_form', 'api', 'import', 'pos', 'customer_service'

    @Column(
            name = "consent_method",
            length = 50)
    public String consentMethod; // 'opt_in', 'opt_out', 'implied'

    @Column(
            name = "ip_address",
            length = 45)
    public String ipAddress; // For audit trail

    @Column(
            name = "user_agent",
            columnDefinition = "TEXT")
    public String userAgent;

    @Column(
            columnDefinition = "TEXT")
    public String notes; // Free-text notes (e.g., "Customer called to unsubscribe")

    @Column(
            name = "consented_at",
            nullable = false)
    public OffsetDateTime consentedAt;

    @PrePersist
    public void prePersist() {
        if (consentedAt == null) {
            consentedAt = OffsetDateTime.now();
        }
    }
}
