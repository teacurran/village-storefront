package villagecompute.storefront.util;

import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.jboss.logging.Logger;

/**
 * Utility for encrypting and decrypting sensitive data using PostgreSQL pgcrypto extension.
 *
 * <p>
 * Provides helpers for encrypting consignor tax information (SSN, EIN) with key versioning for compliance and rotation.
 * Uses pgp_sym_encrypt/decrypt functions with a tenant-scoped encryption key.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I4.T1: Encryption for tax fields</li>
 * <li>Clarification 4: pgcrypto integration</li>
 * <li>§5 Data Governance: PII encryption requirements</li>
 * </ul>
 */
@ApplicationScoped
public class PgCryptoUtil {

    private static final Logger LOG = Logger.getLogger(PgCryptoUtil.class);
    private static final String DEFAULT_KEY_VERSION = "v1";

    @Inject
    EntityManager entityManager;

    /**
     * Encrypt sensitive text using pgcrypto with key versioning.
     *
     * @param plainText
     *            text to encrypt
     * @param tenantId
     *            tenant UUID (used in key derivation)
     * @param keyVersion
     *            encryption key version (for rotation tracking)
     * @return encrypted bytea
     */
    public byte[] encrypt(String plainText, UUID tenantId, String keyVersion) {
        if (plainText == null || plainText.isBlank()) {
            return null;
        }

        try {
            String encryptionKey = deriveEncryptionKey(tenantId, keyVersion);

            Query query = entityManager.createNativeQuery("SELECT pgp_sym_encrypt(?1, ?2)");
            query.setParameter(1, plainText);
            query.setParameter(2, encryptionKey);

            byte[] encrypted = (byte[]) query.getSingleResult();

            LOG.debugf("Encrypted data - tenantId=%s, keyVersion=%s", tenantId, keyVersion);
            return encrypted;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to encrypt data - tenantId=%s", tenantId);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Encrypt sensitive text using default key version.
     *
     * @param plainText
     *            text to encrypt
     * @param tenantId
     *            tenant UUID
     * @return encrypted bytea
     */
    public byte[] encrypt(String plainText, UUID tenantId) {
        return encrypt(plainText, tenantId, DEFAULT_KEY_VERSION);
    }

    /**
     * Decrypt sensitive text using pgcrypto.
     *
     * @param encryptedBytes
     *            encrypted bytea
     * @param tenantId
     *            tenant UUID (used in key derivation)
     * @param keyVersion
     *            encryption key version
     * @return decrypted plain text
     */
    public String decrypt(byte[] encryptedBytes, UUID tenantId, String keyVersion) {
        if (encryptedBytes == null || encryptedBytes.length == 0) {
            return null;
        }

        try {
            String encryptionKey = deriveEncryptionKey(tenantId, keyVersion);

            Query query = entityManager.createNativeQuery("SELECT pgp_sym_decrypt(?1, ?2)");
            query.setParameter(1, encryptedBytes);
            query.setParameter(2, encryptionKey);

            String decrypted = (String) query.getSingleResult();

            LOG.debugf("Decrypted data - tenantId=%s, keyVersion=%s", tenantId, keyVersion);
            return decrypted;

        } catch (Exception e) {
            LOG.errorf(e, "Failed to decrypt data - tenantId=%s", tenantId);
            throw new RuntimeException("Decryption failed", e);
        }
    }

    /**
     * Decrypt sensitive text using default key version.
     *
     * @param encryptedBytes
     *            encrypted bytea
     * @param tenantId
     *            tenant UUID
     * @return decrypted plain text
     */
    public String decrypt(byte[] encryptedBytes, UUID tenantId) {
        return decrypt(encryptedBytes, tenantId, DEFAULT_KEY_VERSION);
    }

    /**
     * Derive encryption key from tenant ID and version.
     *
     * <p>
     * In production, this would fetch from a secure key management service (KMS) or environment-specific vault. For
     * development, generates a deterministic key from tenant ID + version + base secret.
     *
     * @param tenantId
     *            tenant UUID
     * @param keyVersion
     *            key version
     * @return encryption key
     */
    private String deriveEncryptionKey(UUID tenantId, String keyVersion) {
        // IMPORTANT: In production, fetch from KMS or environment variable
        // This is a simplified implementation for demonstration purposes
        String baseSecret = System.getenv("PG_CRYPTO_KEY");
        if (baseSecret == null || baseSecret.isBlank()) {
            baseSecret = "INSECURE_DEV_KEY_DO_NOT_USE_IN_PROD";
            LOG.warn("Using insecure default encryption key - set PG_CRYPTO_KEY environment variable");
        }

        // Derive tenant-specific key (in production, use HMAC or KMS derivation)
        return String.format("%s-%s-%s", baseSecret, tenantId.toString().substring(0, 8), keyVersion);
    }

    /**
     * Re-encrypt data with a new key version (for key rotation).
     *
     * @param encryptedBytes
     *            existing encrypted data
     * @param tenantId
     *            tenant UUID
     * @param oldVersion
     *            old key version
     * @param newVersion
     *            new key version
     * @return re-encrypted data with new key
     */
    public byte[] rotateKey(byte[] encryptedBytes, UUID tenantId, String oldVersion, String newVersion) {
        if (encryptedBytes == null || encryptedBytes.length == 0) {
            return null;
        }

        LOG.infof("Rotating encryption key - tenantId=%s, oldVersion=%s, newVersion=%s", tenantId, oldVersion,
                newVersion);

        String plainText = decrypt(encryptedBytes, tenantId, oldVersion);
        return encrypt(plainText, tenantId, newVersion);
    }

    /**
     * Verify encryption key configuration is valid.
     *
     * @return true if encryption is properly configured
     */
    public boolean verifyConfiguration() {
        try {
            UUID testTenantId = UUID.randomUUID();
            String testData = "test-data-" + System.currentTimeMillis();

            byte[] encrypted = encrypt(testData, testTenantId);
            String decrypted = decrypt(encrypted, testTenantId);

            boolean valid = testData.equals(decrypted);

            if (valid) {
                LOG.info("Encryption configuration verified successfully");
            } else {
                LOG.error("Encryption configuration verification failed - decrypt did not match original");
            }

            return valid;

        } catch (Exception e) {
            LOG.errorf(e, "Encryption configuration verification failed");
            return false;
        }
    }

    /**
     * @return default key version used when none is provided explicitly.
     */
    public String getDefaultKeyVersion() {
        return DEFAULT_KEY_VERSION;
    }
}
