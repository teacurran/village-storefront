package villagecompute.storefront.pos.offline;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Helper for encrypting/decrypting POS device keys using a server-side master key.
 *
 * <p>
 * Ensures device encryption keys never persist in plaintext on the server while enabling offline sync jobs to decrypt
 * queued payloads.
 */
@ApplicationScoped
public class POSOfflineKeyService {

    private static final Logger LOG = Logger.getLogger(POSOfflineKeyService.class);
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final String CIPHER = "AES/GCM/NoPadding";

    @ConfigProperty(
            name = "pos.offline.master-key")
    String encodedMasterKey;

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec masterKey;

    @PostConstruct
    void init() {
        byte[] decoded = Base64.getDecoder().decode(encodedMasterKey);
        if (decoded.length != 32) {
            throw new IllegalStateException("pos.offline.master-key must be a 32-byte Base64 encoded value");
        }
        masterKey = new SecretKeySpec(decoded, "AES");
    }

    /**
     * Encrypt a raw device key for persistence.
     */
    public byte[] encryptDeviceKey(byte[] rawKey) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(rawKey);
            byte[] blob = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(ciphertext, 0, blob, iv.length, ciphertext.length);
            return blob;
        } catch (GeneralSecurityException e) {
            LOG.error("Failed to encrypt POS device key", e);
            throw new IllegalStateException("Unable to encrypt POS device key", e);
        }
    }

    /**
     * Decrypt a stored device key blob.
     */
    public byte[] decryptDeviceKey(byte[] blob) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(blob, 0, iv, 0, IV_LENGTH);

            byte[] ciphertext = new byte[blob.length - IV_LENGTH];
            System.arraycopy(blob, IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            LOG.error("Failed to decrypt POS device key", e);
            throw new IllegalStateException("Unable to decrypt POS device key", e);
        }
    }
}
