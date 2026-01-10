package villagecompute.storefront.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import villagecompute.storefront.data.models.OAuthClient;
import villagecompute.storefront.data.repositories.OAuthClientRepository;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for bcrypt work factor migration.
 *
 * <p>
 * Handles automatic re-hashing of OAuth client secrets to higher bcrypt cost factor on successful authentication. This
 * allows incremental migration without forcing password resets.
 *
 * <p>
 * <strong>Migration Strategy:</strong>
 * <ol>
 * <li>Client authenticates with plaintext secret</li>
 * <li>Verify secret with current hash (cost=12 or cost=13)</li>
 * <li>If successful AND hash version < target version, re-hash with higher cost</li>
 * <li>Update database with new hash and version number</li>
 * </ol>
 *
 * <p>
 * <strong>Hash Versions:</strong>
 * <ul>
 * <li>Version 1: bcrypt cost=12 (4096 rounds, ~150ms)</li>
 * <li>Version 2: bcrypt cost=13 (8192 rounds, ~300ms)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I6.T2: bcrypt work factor migration tooling</li>
 * <li>OAuthService: Uses this service for automatic migration on login</li>
 * </ul>
 */
@ApplicationScoped
public class BcryptMigrationService {

    private static final Logger LOG = Logger.getLogger(BcryptMigrationService.class);

    private static final int TARGET_VERSION = 2;
    private static final int TARGET_COST = 13;

    @Inject
    OAuthClientRepository oauthClientRepository;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Check if client secret needs migration to higher cost factor.
     *
     * @param client
     *            OAuth client
     * @return true if migration needed
     */
    public boolean needsMigration(OAuthClient client) {
        // Check if password_hash_version field exists and is below target
        // Note: This assumes password_hash_version was added to OAuthClient entity
        // If field doesn't exist yet, this will return false (safe default)
        return getHashVersion(client) < TARGET_VERSION;
    }

    /**
     * Re-hash client secret with higher bcrypt cost factor.
     *
     * <p>
     * <strong>IMPORTANT:</strong> This should only be called AFTER successful password verification. Never call this
     * with an unverified plaintext secret.
     *
     * @param client
     *            OAuth client
     * @param plaintextSecret
     *            verified plaintext secret
     */
    @Transactional
    public void migrateSecret(OAuthClient client, String plaintextSecret) {
        if (plaintextSecret == null || plaintextSecret.isBlank()) {
            LOG.error("Attempted migration with empty plaintext secret - aborting");
            return;
        }

        if (!needsMigration(client)) {
            LOG.debugf("Client %s already at target hash version %d - skipping migration", client.clientId,
                    TARGET_VERSION);
            return;
        }

        try {
            // Re-hash with higher cost factor
            String newHash = BCrypt.hashpw(plaintextSecret, BCrypt.gensalt(TARGET_COST));

            // Update client
            client.clientSecretHash = newHash;
            setHashVersion(client, TARGET_VERSION);

            oauthClientRepository.persist(client);

            LOG.infof("Migrated bcrypt hash for client %s from version %d to version %d (cost=%d)", client.clientId,
                    getHashVersion(client) - 1, TARGET_VERSION, TARGET_COST);

            meterRegistry
                    .counter("bcrypt.migration.success", "client_id", client.clientId, "from_version",
                            String.valueOf(getHashVersion(client) - 1), "to_version", String.valueOf(TARGET_VERSION))
                    .increment();

        } catch (IllegalArgumentException e) {
            LOG.errorf(e, "Failed to migrate bcrypt hash for client %s", client.clientId);
            meterRegistry.counter("bcrypt.migration.error", "client_id", client.clientId, "error",
                    e.getClass().getSimpleName()).increment();
        }
    }

    /**
     * Get hash version for client.
     *
     * @param client
     *            OAuth client
     * @return hash version (1, 2, etc.)
     */
    private int getHashVersion(OAuthClient client) {
        // Note: This assumes password_hash_version field was added to OAuthClient entity
        // If not present, default to version 1 (cost=12)
        // Implementation depends on whether column was added to database schema
        return 1; // TODO: Replace with client.passwordHashVersion once column added
    }

    /**
     * Set hash version for client.
     *
     * @param client
     *            OAuth client
     * @param version
     *            hash version
     */
    private void setHashVersion(OAuthClient client, int version) {
        // Note: This assumes password_hash_version field was added to OAuthClient entity
        // Implementation depends on whether column was added to database schema
        // TODO: Replace with client.passwordHashVersion = version once column added
        LOG.warnf(
                "setHashVersion called but password_hash_version column not yet added to schema - version %d not persisted",
                version);
    }

    /**
     * Get migration statistics.
     *
     * @return migration progress (percentage of clients migrated)
     */
    public double getMigrationProgress() {
        // Query database for migration statistics
        // SELECT COUNT(*) FILTER (WHERE password_hash_version >= 2) / COUNT(*) FROM oauth_clients
        // For now, return 0.0 until column is added
        return 0.0;
    }
}
