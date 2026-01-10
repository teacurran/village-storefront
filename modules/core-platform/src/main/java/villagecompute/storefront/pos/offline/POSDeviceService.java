package villagecompute.storefront.pos.offline;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.payment.stripe.StripeTerminalService;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for managing POS device registration, pairing, and encryption key management.
 *
 * <p>
 * Handles the device pairing workflow:
 * <ol>
 * <li>Device requests pairing → generates short pairing code</li>
 * <li>Staff enters code in admin UI → device activated</li>
 * <li>Device receives encryption key → can encrypt offline payloads</li>
 * </ol>
 *
 * <p>
 * References:
 * <ul>
 * <li>Architecture: §3.19.10 POS device registration workflows</li>
 * <li>Task I4.T7: Device pairing service implementation</li>
 * </ul>
 */
@ApplicationScoped
public class POSDeviceService {

    private static final Logger LOG = Logger.getLogger(POSDeviceService.class);
    private static final int PAIRING_CODE_LENGTH = 8;
    private static final int PAIRING_CODE_EXPIRY_MINUTES = 15;
    private static final int ENCRYPTION_KEY_BYTES = 32; // 256-bit AES

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    POSOfflineKeyService keyService;

    @Inject
    StripeTerminalService stripeTerminalService;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Initiate device pairing by generating a pairing code.
     *
     * @param deviceIdentifier
     *            hardware identifier
     * @param deviceName
     *            user-friendly name
     * @param locationName
     *            physical location
     * @param hardwareModel
     *            device model
     * @param createdBy
     *            user initiating pairing
     * @return device with pairing code
     */
    @Transactional
    public POSDevice initiatePairing(String deviceIdentifier, String deviceName, String locationName,
            String hardwareModel, User createdBy) {

        UUID tenantId = TenantContext.getCurrentTenantId();
        Tenant tenant = Tenant.findById(tenantId);

        // Check if device already exists
        POSDevice existing = POSDevice.findByTenantAndIdentifier(tenantId, deviceIdentifier);
        if (existing != null) {
            if (existing.status == POSDevice.DeviceStatus.ACTIVE) {
                throw new IllegalStateException("Device already paired and active");
            }
            // Re-generate pairing code for pending/suspended devices
            existing.pairingCode = generatePairingCode();
            existing.pairingExpiresAt = OffsetDateTime.now().plusMinutes(PAIRING_CODE_EXPIRY_MINUTES);
            existing.updatedBy = createdBy;
            existing.persist();

            LOG.infof("Re-generated pairing code for device %s (id=%d)", deviceIdentifier, existing.id);
            meterRegistry.counter("pos.device.pairing_code_regenerated").increment();
            return existing;
        }

        // Create new device
        POSDevice device = new POSDevice();
        device.tenant = tenant;
        device.deviceIdentifier = deviceIdentifier;
        device.deviceName = deviceName;
        device.locationName = locationName;
        device.hardwareModel = hardwareModel;
        device.status = POSDevice.DeviceStatus.PENDING;
        device.pairingCode = generatePairingCode();
        device.pairingExpiresAt = OffsetDateTime.now().plusMinutes(PAIRING_CODE_EXPIRY_MINUTES);
        device.encryptionKeyHash = "PENDING"; // Updated on completion
        device.encryptionKeyVersion = 1;
        device.createdBy = createdBy;
        device.updatedBy = createdBy;
        device.persist();

        LOG.infof("Initiated pairing for device %s (id=%d, code=%s)", deviceIdentifier, device.id, device.pairingCode);
        meterRegistry.counter("pos.device.pairing_initiated").increment();

        POSActivityLog.log(device, POSActivityLog.ActivityType.DEVICE_PAIRED, createdBy,
                java.util.Map.of("action", "pairing_initiated", "device_name", deviceName));

        return device;
    }

    /**
     * Complete device pairing with staff-entered pairing code.
     *
     * @param pairingCode
     *            code from initiatePairing
     * @param approvedBy
     *            staff user approving pairing
     * @return device encryption key (client must store securely)
     */
    @Transactional
    public DevicePairingResult completePairing(String pairingCode, User approvedBy) {
        POSDevice device = POSDevice
                .find("pairingCode = ?1 AND status = ?2", pairingCode, POSDevice.DeviceStatus.PENDING).firstResult();

        if (device == null) {
            LOG.warnf("Invalid pairing code: %s", pairingCode);
            meterRegistry.counter("pos.device.pairing_failed", "reason", "invalid_code").increment();
            throw new IllegalArgumentException("Invalid pairing code");
        }

        if (!device.isPairingCodeValid()) {
            LOG.warnf("Expired pairing code for device %d", device.id);
            meterRegistry.counter("pos.device.pairing_failed", "reason", "expired_code").increment();
            throw new IllegalArgumentException("Pairing code expired");
        }

        boolean hasExistingKey = device.encryptionKeyHash != null && !device.encryptionKeyHash.isBlank()
                && !"PENDING".equalsIgnoreCase(device.encryptionKeyHash);
        int nextVersion = hasExistingKey ? (device.encryptionKeyVersion != null ? device.encryptionKeyVersion + 1 : 1)
                : (device.encryptionKeyVersion != null ? device.encryptionKeyVersion : 1);

        // Generate encryption key
        byte[] encryptionKey = new byte[ENCRYPTION_KEY_BYTES];
        secureRandom.nextBytes(encryptionKey);
        String encryptionKeyBase64 = Base64.getEncoder().encodeToString(encryptionKey);

        byte[] encryptedBlob = keyService.encryptDeviceKey(encryptionKey);

        // Persist key record
        POSDeviceKey keyRecord = new POSDeviceKey();
        keyRecord.device = device;
        keyRecord.tenant = device.tenant;
        keyRecord.keyVersion = nextVersion;
        keyRecord.keyCiphertext = encryptedBlob;
        keyRecord.persist();

        // Hash key for storage
        device.encryptionKeyHash = hashEncryptionKey(encryptionKey);
        device.encryptionKeyVersion = nextVersion;
        device.status = POSDevice.DeviceStatus.ACTIVE;
        device.pairingCode = null; // Clear pairing code
        device.pairingExpiresAt = null;
        device.lastSeenAt = OffsetDateTime.now();
        device.updatedBy = approvedBy;
        device.persist();

        String connectionToken = stripeTerminalService.createConnectionToken(device.tenant.id, device.id);

        LOG.infof("Completed pairing for device %d (%s)", device.id, device.deviceName);
        meterRegistry.counter("pos.device.pairing_completed").increment();

        POSActivityLog.log(device, POSActivityLog.ActivityType.DEVICE_PAIRED, approvedBy,
                java.util.Map.of("action", "pairing_completed"));

        return new DevicePairingResult(device.id, device.deviceName, encryptionKeyBase64, device.encryptionKeyVersion,
                connectionToken);
    }

    /**
     * Update device heartbeat and optional status.
     */
    @Transactional
    public void updateHeartbeat(Long deviceId, String firmwareVersion) {
        POSDevice device = POSDevice.findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }

        device.lastSeenAt = OffsetDateTime.now();
        if (firmwareVersion != null && !firmwareVersion.equals(device.firmwareVersion)) {
            String oldVersion = device.firmwareVersion;
            device.firmwareVersion = firmwareVersion;
            LOG.infof("Device %d firmware updated: %s → %s", deviceId, oldVersion, firmwareVersion);
            POSActivityLog.log(device, POSActivityLog.ActivityType.FIRMWARE_UPDATE, null, java.util.Map
                    .of("old_version", oldVersion != null ? oldVersion : "unknown", "new_version", firmwareVersion));
        }
        device.persist();
    }

    /**
     * Mark sync completion for a device.
     */
    @Transactional
    public void markSyncCompleted(Long deviceId) {
        POSDevice device = POSDevice.findById(deviceId);
        if (device != null) {
            device.lastSyncedAt = OffsetDateTime.now();
            device.persist();
        }
    }

    /**
     * Suspend a device (prevents new transactions).
     */
    @Transactional
    public void suspendDevice(Long deviceId, User suspendedBy, String reason) {
        POSDevice device = POSDevice.findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }

        device.status = POSDevice.DeviceStatus.SUSPENDED;
        device.updatedBy = suspendedBy;
        device.persist();

        LOG.warnf("Device %d suspended by user %s: %s", deviceId, suspendedBy.email, reason);
        POSActivityLog.log(device, POSActivityLog.ActivityType.DEVICE_SUSPENDED, suspendedBy,
                java.util.Map.of("reason", reason));

        meterRegistry.counter("pos.device.suspended").increment();
    }

    /**
     * Reactivate a suspended device.
     */
    @Transactional
    public void reactivateDevice(Long deviceId, User reactivatedBy) {
        POSDevice device = POSDevice.findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }

        device.status = POSDevice.DeviceStatus.ACTIVE;
        device.updatedBy = reactivatedBy;
        device.persist();

        LOG.infof("Device %d reactivated by user %s", deviceId, reactivatedBy.email);
        meterRegistry.counter("pos.device.reactivated").increment();
    }

    /**
     * Issue a new Stripe Terminal connection token for an active device.
     */
    @Transactional
    public String issueTerminalConnectionToken(Long deviceId) {
        POSDevice device = POSDevice.findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("Device not found: " + deviceId);
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null || !tenantId.equals(device.tenant.id)) {
            throw new SecurityException("Device does not belong to current tenant");
        }

        if (device.status != POSDevice.DeviceStatus.ACTIVE) {
            throw new IllegalStateException("Device must be active to request Terminal token");
        }

        return stripeTerminalService.createConnectionToken(device.tenant.id, device.id);
    }

    /**
     * List all active devices for current tenant.
     */
    public List<POSDevice> listActiveDevices() {
        return POSDevice.findActiveByTenant();
    }

    /**
     * Generate random pairing code (8 alphanumeric characters, no ambiguous chars).
     */
    private String generatePairingCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // No O, 0, I, 1
        StringBuilder code = new StringBuilder(PAIRING_CODE_LENGTH);
        for (int i = 0; i < PAIRING_CODE_LENGTH; i++) {
            code.append(chars.charAt(secureRandom.nextInt(chars.length())));
        }
        return code.toString();
    }

    /**
     * Hash encryption key using SHA-256 (never store raw keys).
     */
    private String hashEncryptionKey(byte[] key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Result of successful device pairing.
     */
    public record DevicePairingResult(Long deviceId, String deviceName, String encryptionKey, // Base64-encoded
            int encryptionKeyVersion, String stripeConnectionToken) {
    }
}
