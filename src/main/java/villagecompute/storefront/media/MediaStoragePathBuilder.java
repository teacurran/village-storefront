package villagecompute.storefront.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Utility for constructing tenant-scoped media storage keys.
 *
 * <p>
 * Keys follow architecture guidance (§4.1.6) by including tenant prefixes and hashed filename components for
 * cache-busting without CDN purge calls.
 */
public final class MediaStoragePathBuilder {

    private MediaStoragePathBuilder() {
    }

    /**
     * Builds the storage key for the original uploaded object.
     *
     * @param tenantId
     *            tenant identifier
     * @param assetType
     *            asset type (image|video)
     * @param assetId
     *            asset identifier
     * @param originalFilename
     *            filename provided by client
     * @return tenant-scoped storage key
     */
    public static String buildOriginalKey(UUID tenantId, String assetType, UUID assetId, String originalFilename) {
        String sanitized = sanitizeFilename(originalFilename);
        String hashed = hashComponent(assetId + ":original:" + sanitized);
        return String.format("%s/media/%s/%s/original/%s_%s", tenantId, assetType, assetId, hashed, sanitized);
    }

    /**
     * Builds the storage key for a derivative object.
     *
     * @param tenantId
     *            tenant identifier
     * @param assetType
     *            asset type
     * @param assetId
     *            asset identifier
     * @param derivativeType
     *            derivative type (thumbnail, hls_720p, etc.)
     * @param filename
     *            derivative filename
     * @return tenant-scoped derivative key
     */
    public static String buildDerivativeKey(UUID tenantId, String assetType, UUID assetId, String derivativeType,
            String filename) {
        String sanitized = sanitizeFilename(filename);
        String hashed = hashComponent(assetId + ":" + derivativeType + ":" + sanitized);
        return String.format("%s/media/%s/%s/derivatives/%s/%s_%s", tenantId, assetType, assetId, derivativeType,
                hashed, sanitized);
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "media.bin";
        }
        String normalized = Normalizer.normalize(filename, Normalizer.Form.NFKD);
        String lower = normalized.toLowerCase(Locale.ROOT);
        return lower.replaceAll("[^a-z0-9._-]", "-");
    }

    private static String hashComponent(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable for media key hashing", e);
        }
    }
}
