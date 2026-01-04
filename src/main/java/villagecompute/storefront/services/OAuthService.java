package villagecompute.storefront.services;

import java.time.OffsetDateTime;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import villagecompute.storefront.data.models.OAuthClient;
import villagecompute.storefront.data.repositories.OAuthClientRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service layer for OAuth client authentication and validation.
 *
 * <p>
 * Provides business logic for authenticating OAuth clients using client credentials flow. Handles secret verification
 * (SHA-256 with salt) and updates last-used timestamps.
 *
 * <p>
 * <strong>Security Note:</strong> Client secrets are hashed using BCrypt (12 rounds) to align with architecture and
 * OpenAPI documentation.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: OAuth client credentials authentication</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@ApplicationScoped
public class OAuthService {

    private static final Logger LOG = Logger.getLogger(OAuthService.class);

    @Inject
    OAuthClientRepository oauthClientRepository;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Authenticate OAuth client using client credentials.
     *
     * @param clientId
     *            client identifier
     * @param clientSecret
     *            client secret (plaintext)
     * @return authenticated OAuth client if valid
     */
    @Transactional
    public Optional<OAuthClient> authenticate(String clientId, String clientSecret) {
        LOG.debugf("Authenticating OAuth client - clientId=%s", clientId);

        Optional<OAuthClient> clientOpt = oauthClientRepository.findActiveByClientId(clientId);

        if (clientOpt.isEmpty()) {
            LOG.warnf("OAuth client not found or inactive - clientId=%s", clientId);
            meterRegistry.counter("oauth.auth.failed", "reason", "client_not_found").increment();
            return Optional.empty();
        }

        OAuthClient client = clientOpt.get();

        if (!verifySecret(clientSecret, client.clientSecretHash)) {
            LOG.warnf("OAuth client secret verification failed - clientId=%s", clientId);
            meterRegistry.counter("oauth.auth.failed", "reason", "invalid_secret", "client_id", clientId).increment();
            return Optional.empty();
        }

        // Update last used timestamp
        client.lastUsedAt = OffsetDateTime.now();
        oauthClientRepository.persist(client);

        LOG.infof("OAuth client authenticated successfully - clientId=%s, tenantId=%s, scopes=%s", clientId,
                client.tenant.id, client.scopes);
        meterRegistry.counter("oauth.auth.success", "client_id", clientId, "tenant_id", client.tenant.id.toString())
                .increment();

        return Optional.of(client);
    }

    /**
     * Verify client secret against stored hash.
     *
     * <p>
     * Expected format: salt:hash (both Base64-encoded)
     *
     * @param plainSecret
     *            plaintext secret
     * @param storedHash
     *            salted SHA-256 hash (format: "salt:hash")
     * @return true if secret matches
     */
    private boolean verifySecret(String plainSecret, String storedHash) {
        if (plainSecret == null || plainSecret.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }

        try {
            return BCrypt.checkpw(plainSecret, storedHash);
        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Invalid BCrypt hash format");
            return false;
        }
    }

    /**
     * Hash client secret using SHA-256 with random salt.
     *
     * @param plainSecret
     *            plaintext secret
     * @return salted hash (format: "salt:hash", both Base64-encoded)
     */
    public String hashSecret(String plainSecret) {
        if (plainSecret == null || plainSecret.isBlank()) {
            throw new IllegalArgumentException("Client secret cannot be blank");
        }

        try {
            return BCrypt.hashpw(plainSecret, BCrypt.gensalt(12));
        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Error hashing client secret");
            throw new RuntimeException("Failed to hash client secret", e);
        }
    }
}
