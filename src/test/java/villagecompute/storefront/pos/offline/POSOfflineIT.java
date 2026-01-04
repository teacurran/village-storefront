package villagecompute.storefront.pos.offline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.payment.PaymentProvider;
import villagecompute.storefront.payment.PaymentProvider.PaymentIntentResult;
import villagecompute.storefront.payment.PaymentProvider.PaymentStatus;
import villagecompute.storefront.payment.stripe.StripeTerminalService;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectMock;

/**
 * Integration tests for POS offline queue functionality.
 *
 * Tests device pairing, offline queue operations, and sync workflows.
 *
 * References: - Task I4.T7: POS offline queue integration tests - Acceptance Criteria: Offline queue persists encrypted
 * payloads, sync resumes automatically
 */
@QuarkusTest
public class POSOfflineIT {

    @Inject
    POSDeviceService deviceService;

    @Inject
    OfflineSyncProcessor syncProcessor;

    @InjectMock
    StripeTerminalService stripeTerminalService;

    @InjectMock
    PaymentProvider paymentProvider;

    private Tenant testTenant;
    private User testUser;
    private POSDeviceService.DevicePairingResult lastPairingResult;

    @BeforeEach
    @Transactional
    public void setup() {
        // Create test tenant
        testTenant = new Tenant();
        testTenant.id = java.util.UUID.randomUUID();
        testTenant.subdomain = "test-pos";
        testTenant.persist();

        // Create test user
        testUser = new User();
        testUser.id = java.util.UUID.randomUUID();
        testUser.tenant = testTenant;
        testUser.email = "test@example.com";
        testUser.persist();

        TenantContext.setCurrentTenantId(testTenant.id);

        when(stripeTerminalService.createConnectionToken(any(), anyLong())).thenReturn("term_tok_test");
        when(paymentProvider.createIntent(any()))
                .thenReturn(new PaymentIntentResult("pi_test", "secret", PaymentStatus.CAPTURED, java.util.Map.of()));
    }

    @Test
    @Transactional
    public void testDevicePairingWorkflow() {
        // Step 1: Initiate pairing
        POSDevice device = deviceService.initiatePairing("MAC-123456", "Test Register 1", "Front Counter", "iPad Pro",
                testUser);

        assertNotNull(device.pairingCode);
        assertNotNull(device.pairingExpiresAt);
        assertEquals(POSDevice.DeviceStatus.PENDING, device.status);
        assertEquals(8, device.pairingCode.length());

        // Step 2: Complete pairing
        POSDeviceService.DevicePairingResult result = deviceService.completePairing(device.pairingCode, testUser);

        assertEquals(device.id, result.deviceId());
        assertNotNull(result.encryptionKey());
        assertEquals(1, result.encryptionKeyVersion());
        assertNotNull(result.stripeConnectionToken());

        // Verify device activated
        POSDevice paired = POSDevice.findById(device.id);
        assertEquals(POSDevice.DeviceStatus.ACTIVE, paired.status);
        assertNull(paired.pairingCode);
        assertNotNull(paired.encryptionKeyHash);
    }

    @Test
    @Transactional
    public void testDevicePairingCodeExpiration() {
        POSDevice device = deviceService.initiatePairing("MAC-789012", "Test Register 2", "Back Office", "iPad",
                testUser);

        // Manually expire pairing code
        device.pairingExpiresAt = java.time.OffsetDateTime.now().minusMinutes(1);
        device.persist();

        // Attempt to complete pairing with expired code
        assertThrows(IllegalArgumentException.class, () -> {
            deviceService.completePairing(device.pairingCode, testUser);
        });
    }

    @Test
    @Transactional
    public void testOfflineQueueEnqueue() {
        // Setup paired device
        POSDevice device = createPairedDevice();

        // Create queue entry
        POSOfflineQueue queueEntry = new POSOfflineQueue();
        queueEntry.tenant = testTenant;
        queueEntry.device = device;
        queueEntry.encryptedPayload = "encrypted-payload".getBytes();
        queueEntry.encryptionKeyVersion = 1;
        queueEntry.encryptionIv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        queueEntry.localTransactionId = java.util.UUID.randomUUID().toString();
        queueEntry.transactionTimestamp = java.time.OffsetDateTime.now();
        queueEntry.transactionAmount = new BigDecimal("99.99");
        queueEntry.idempotencyKey = POSOfflineQueue.generateIdempotencyKey(testTenant.id, device.id,
                queueEntry.localTransactionId);
        queueEntry.persist();

        // Verify queue entry created
        assertNotNull(queueEntry.id);
        assertEquals(POSOfflineQueue.SyncStatus.QUEUED, queueEntry.syncStatus);
        assertEquals(0, queueEntry.syncAttemptCount);

        // Verify idempotency key unique
        POSOfflineQueue duplicate = new POSOfflineQueue();
        duplicate.tenant = testTenant;
        duplicate.device = device;
        duplicate.encryptedPayload = "different-payload".getBytes();
        duplicate.encryptionKeyVersion = 1;
        duplicate.encryptionIv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        duplicate.localTransactionId = queueEntry.localTransactionId;
        duplicate.transactionTimestamp = java.time.OffsetDateTime.now();
        duplicate.transactionAmount = new BigDecimal("50.00");
        duplicate.idempotencyKey = queueEntry.idempotencyKey; // Same idempotency key

        assertThrows(Exception.class, () -> {
            duplicate.persist(); // Should fail due to unique constraint
        });
    }

    @Test
    @Transactional
    public void testQueueCountByDevice() {
        POSDevice device = createPairedDevice();

        // Add multiple queue entries
        for (int i = 0; i < 5; i++) {
            POSOfflineQueue entry = new POSOfflineQueue();
            entry.tenant = testTenant;
            entry.device = device;
            entry.encryptedPayload = ("payload-" + i).getBytes();
            entry.encryptionKeyVersion = 1;
            entry.encryptionIv = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
            entry.localTransactionId = java.util.UUID.randomUUID().toString();
            entry.transactionTimestamp = java.time.OffsetDateTime.now();
            entry.transactionAmount = new BigDecimal("10.00");
            entry.idempotencyKey = POSOfflineQueue.generateIdempotencyKey(testTenant.id, device.id,
                    entry.localTransactionId);
            entry.persist();
        }

        // Verify count
        long count = POSOfflineQueue.countQueuedByDevice(device.id);
        assertEquals(5, count);
    }

    @Test
    @Transactional
    public void testDeviceSuspension() {
        POSDevice device = createPairedDevice();

        // Suspend device
        deviceService.suspendDevice(device.id, testUser, "Testing suspension");

        // Verify suspended
        POSDevice suspended = POSDevice.findById(device.id);
        assertEquals(POSDevice.DeviceStatus.SUSPENDED, suspended.status);

        // Verify activity log
        long logCount = POSActivityLog.count("device = ?1 AND activityType = ?2", device,
                POSActivityLog.ActivityType.DEVICE_SUSPENDED);
        assertEquals(1, logCount);

        // Reactivate
        deviceService.reactivateDevice(device.id, testUser);

        POSDevice reactivated = POSDevice.findById(device.id);
        assertEquals(POSDevice.DeviceStatus.ACTIVE, reactivated.status);
    }

    @Test
    @Transactional
    public void testActivityLogging() {
        POSDevice device = createPairedDevice();

        // Log activities
        POSActivityLog.log(device, POSActivityLog.ActivityType.LOGIN, testUser,
                java.util.Map.of("session", "test-123"));

        POSActivityLog.log(device, POSActivityLog.ActivityType.CASH_DRAWER_OPEN, testUser,
                java.util.Map.of("reason", "start_shift"));

        // Verify logs
        List<POSActivityLog> logs = POSActivityLog.find("device = ?1 ORDER BY occurredAt DESC", device).list();
        assertEquals(3, logs.size()); // 2 manual + 1 from pairing

        POSActivityLog latestLog = logs.get(0);
        assertEquals(POSActivityLog.ActivityType.CASH_DRAWER_OPEN, latestLog.activityType);
        assertEquals("start_shift", latestLog.metadata.get("reason"));
    }

    @Test
    @Transactional
    public void testOfflineSyncProcessorDecryptsAndCompletesQueue() throws Exception {
        POSDevice device = createPairedDevice();
        assertNotNull(lastPairingResult);

        String localTransactionId = java.util.UUID.randomUUID().toString();
        byte[] key = java.util.Base64.getDecoder().decode(lastPairingResult.encryptionKey());
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        String payloadJson = """
                {"localTransactionId":"%s","totalAmount":99.99,"currency":"USD","customerId":"cust_1","paymentMethodId":"pm_card","items":[]}
                """
                .formatted(localTransactionId);
        byte[] encryptedPayload = encryptPayload(key, iv, payloadJson);

        POSOfflineQueue queueEntry = new POSOfflineQueue();
        queueEntry.tenant = testTenant;
        queueEntry.device = device;
        queueEntry.encryptedPayload = encryptedPayload;
        queueEntry.encryptionKeyVersion = lastPairingResult.encryptionKeyVersion();
        queueEntry.encryptionIv = iv;
        queueEntry.localTransactionId = localTransactionId;
        queueEntry.transactionTimestamp = java.time.OffsetDateTime.now();
        queueEntry.transactionAmount = new BigDecimal("99.99");
        queueEntry.staffUser = testUser;
        queueEntry.idempotencyKey = POSOfflineQueue.generateIdempotencyKey(testTenant.id, device.id,
                localTransactionId);
        queueEntry.persist();

        syncProcessor.enqueueSync(queueEntry);
        boolean processed = syncProcessor.processNextJob();
        assertTrue(processed);

        POSOfflineQueue refreshed = POSOfflineQueue.findById(queueEntry.id);
        assertEquals(POSOfflineQueue.SyncStatus.COMPLETED, refreshed.syncStatus);
        assertNotNull(refreshed.syncCompletedAt);

        POSOfflineTransaction audit = POSOfflineTransaction.find("queueEntry = ?1", refreshed).firstResult();
        assertNotNull(audit);
        assertEquals(localTransactionId, audit.localTransactionId);
    }

    private byte[] encryptPayload(byte[] key, byte[] iv, String json) throws Exception {
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(128, iv);
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), spec);
        return cipher.doFinal(json.getBytes(StandardCharsets.UTF_8));
    }

    // Helper method to create a paired device
    private POSDevice createPairedDevice() {
        POSDevice device = deviceService.initiatePairing(
                "MAC-" + java.util.UUID.randomUUID().toString().substring(0, 8), "Test Device", "Test Location",
                "Test Model", testUser);

        lastPairingResult = deviceService.completePairing(device.pairingCode, testUser);

        return POSDevice.findById(device.id);
    }
}
