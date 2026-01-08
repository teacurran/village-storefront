package villagecompute.storefront.data.models;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;

/**
 * OAuth client credentials entity for headless API access.
 *
 * <p>
 * Represents an OAuth 2.0 client using the Client Credentials flow. Each client is scoped to a specific tenant and has
 * a set of allowed scopes (e.g., catalog:read, cart:write, orders:read).
 *
 * <p>
 * <strong>Security:</strong>
 * <ul>
 * <li>Client secrets are stored hashed (bcrypt) in the database</li>
 * <li>Clients can be revoked by setting {@code active = false}</li>
 * <li>Rate limits are enforced per client ID via in-memory token bucket</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Headless API with OAuth client credentials</li>
 * <li>Architecture: Section 5 - Contract Patterns (OAuth scopes)</li>
 * <li>Architecture: Section 6 - Safety Net (token bucket rate limiting)</li>
 * </ul>
 */
@Entity
@Table(
        name = "oauth_clients")
public class OAuthClient extends PanacheEntityBase {

    @Id
    @GeneratedValue(
            strategy = GenerationType.UUID)
    public UUID id;

    @ManyToOne(
            fetch = FetchType.LAZY)
    @JoinColumn(
            name = "tenant_id",
            nullable = false)
    public Tenant tenant;

    @Column(
            name = "client_id",
            nullable = false,
            unique = true,
            length = 64)
    public String clientId;

    @Column(
            name = "client_secret_hash",
            nullable = false,
            length = 255)
    public String clientSecretHash;

    @Column(
            name = "name",
            nullable = false,
            length = 255)
    public String name;

    @Column(
            name = "description",
            columnDefinition = "TEXT")
    public String description;

    @ElementCollection(
            fetch = FetchType.EAGER)
    @CollectionTable(
            name = "oauth_client_scopes",
            joinColumns = @JoinColumn(
                    name = "oauth_client_id"))
    @Column(
            name = "scope",
            length = 64)
    public Set<String> scopes = new HashSet<>();

    @Column(
            name = "active",
            nullable = false)
    public boolean active = true;

    @Column(
            name = "rate_limit_per_minute",
            nullable = false)
    public int rateLimitPerMinute = 5000;

    @Column(
            name = "created_at",
            nullable = false,
            updatable = false)
    public OffsetDateTime createdAt;

    @Column(
            name = "updated_at",
            nullable = false)
    public OffsetDateTime updatedAt;

    @Column(
            name = "last_used_at")
    public OffsetDateTime lastUsedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /**
     * Check if client has a specific scope.
     *
     * @param scope
     *            scope to check
     * @return true if client has scope
     */
    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
