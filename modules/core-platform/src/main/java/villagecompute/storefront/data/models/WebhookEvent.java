package villagecompute.storefront.data.models;

import java.time.Instant;

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
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * Webhook event persistence for idempotency and audit trail. Stores raw webhook payloads from payment providers to
 * prevent duplicate processing.
 *
 * Indexed by provider_event_id for O(1) duplicate detection.
 */
@Entity
@Table(
        name = "webhook_events",
        indexes = {@Index(
                name = "idx_webhook_provider_event_id",
                columnList = "provider_event_id",
                unique = true),
                @Index(
                        name = "idx_webhook_tenant_id",
                        columnList = "tenant_id"),
                @Index(
                        name = "idx_webhook_processed",
                        columnList = "processed")})
public class WebhookEvent extends PanacheEntityBase {

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
            name = "provider_event_id",
            nullable = false,
            unique = true,
            length = 255)
    public String providerEventId; // Provider's unique event identifier

    @Column(
            name = "event_type",
            nullable = false,
            length = 100)
    public String eventType; // "payment_intent.succeeded", etc.

    @Column(
            name = "payload",
            nullable = false,
            columnDefinition = "TEXT")
    public String payload; // Raw JSON payload

    @Column(
            name = "processed",
            nullable = false)
    public boolean processed = false;

    @Column(
            name = "processing_error",
            columnDefinition = "TEXT")
    public String processingError; // Error message if processing failed

    @Column(
            name = "received_at",
            nullable = false)
    public Instant receivedAt;

    @Column(
            name = "processed_at")
    public Instant processedAt;

    @Version
    @Column(
            name = "version")
    public Long version;

    @PrePersist
    public void prePersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (tenant == null && TenantContext.hasContext()) {
            tenant = Tenant.findById(TenantContext.getCurrentTenantId());
        }
    }

    /**
     * Find a webhook event by provider event ID.
     *
     * @param providerEventId
     *            Provider's event identifier
     * @return WebhookEvent or null
     */
    public static WebhookEvent findByProviderEventId(String providerEventId) {
        return find("providerEventId", providerEventId).firstResult();
    }

    /**
     * Check if a webhook event has already been received.
     *
     * @param providerEventId
     *            Provider's event identifier
     * @return true if event exists
     */
    public static boolean exists(String providerEventId) {
        return count("providerEventId", providerEventId) > 0;
    }
}
