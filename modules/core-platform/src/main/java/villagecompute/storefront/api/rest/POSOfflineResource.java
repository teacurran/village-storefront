package villagecompute.storefront.api.rest;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.User;
import villagecompute.storefront.pos.offline.OfflineSyncProcessor;
import villagecompute.storefront.pos.offline.POSDevice;
import villagecompute.storefront.pos.offline.POSDeviceService;
import villagecompute.storefront.pos.offline.POSOfflineQueue;
import villagecompute.storefront.pos.offline.POSOfflineQueue.SyncPriority;
import villagecompute.storefront.pos.offline.POSOfflineTransaction;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * REST API for POS offline queue management and device pairing.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>POST /api/pos/devices/pair - Initiate device pairing</li>
 * <li>POST /api/pos/devices/complete-pairing - Complete pairing with code</li>
 * <li>GET /api/pos/devices - List active devices</li>
 * <li>POST /api/pos/offline/upload - Upload encrypted offline batch</li>
 * <li>GET /api/pos/offline/status/{deviceId} - Get sync status</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Architecture: §3.19.10 POS offline flows</li>
 * <li>Task I4.T7: POS offline queue REST endpoints</li>
 * </ul>
 */
@Path("/api/pos")
@Tag(
        name = "POS Offline",
        description = "POS device management and offline transaction sync")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class POSOfflineResource {

    private static final Logger LOG = Logger.getLogger(POSOfflineResource.class);

    @Inject
    POSDeviceService deviceService;

    @Inject
    OfflineSyncProcessor syncProcessor;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Initiate device pairing workflow.
     */
    @POST
    @Path("/devices/pair")
    @Transactional
    @Operation(
            summary = "Initiate device pairing",
            description = "Generate pairing code for new POS device")
    public Response initiatePairing(DevicePairingRequest request) {
        if (request == null || request.deviceIdentifier() == null || request.deviceIdentifier().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Device identifier required").build();
        }

        LOG.infof("Initiating pairing for device: %s", request.deviceIdentifier);

        POSDevice device = deviceService.initiatePairing(request.deviceIdentifier, request.deviceName,
                request.locationName, request.hardwareModel, resolveCurrentUser());

        return Response.ok(new DevicePairingResponse(device.id, device.deviceName, device.pairingCode,
                device.pairingExpiresAt != null ? device.pairingExpiresAt.toString() : null)).build();
    }

    /**
     * Complete device pairing with staff-entered code.
     */
    @POST
    @Path("/devices/complete-pairing")
    @Transactional
    @Operation(
            summary = "Complete device pairing",
            description = "Activate device using pairing code")
    public Response completePairing(CompletePairingRequest request) {
        if (request == null || request.pairingCode() == null || request.pairingCode().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Pairing code required").build();
        }
        LOG.infof("Completing pairing with code: %s", request.pairingCode);

        POSDeviceService.DevicePairingResult result = deviceService.completePairing(request.pairingCode,
                resolveCurrentUser());

        return Response.ok(new PairingCompletionResponse(result.deviceId(), result.deviceName(), result.encryptionKey(),
                result.encryptionKeyVersion(), result.stripeConnectionToken())).build();
    }

    /**
     * List all active devices for current tenant.
     */
    @GET
    @Path("/devices")
    @Operation(
            summary = "List POS devices",
            description = "Get all active POS devices for current tenant")
    public Response listDevices() {
        List<POSDevice> devices = deviceService.listActiveDevices();
        List<DeviceDto> deviceDtos = devices.stream()
                .map(d -> new DeviceDto(d.id, d.deviceName, d.locationName, d.hardwareModel, d.firmwareVersion,
                        d.status.name(), d.lastSeenAt != null ? d.lastSeenAt.toString() : null,
                        d.lastSyncedAt != null ? d.lastSyncedAt.toString() : null))
                .toList();

        return Response.ok(deviceDtos).build();
    }

    /**
     * Upload encrypted offline transaction batch.
     */
    @POST
    @Path("/offline/upload")
    @Transactional
    @Operation(
            summary = "Upload offline batch",
            description = "Upload encrypted offline transactions for sync")
    public Response uploadOfflineBatch(OfflineBatchUploadRequest request) {
        if (request == null || request.transactions() == null || request.transactions().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("At least one transaction required").build();
        }

        LOG.infof("Uploading offline batch: deviceId=%d, count=%d", request.deviceId, request.transactions.size());

        POSDevice device = POSDevice.findById(request.deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Device not found").build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null || !tenantId.equals(device.tenant.id)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Device does not belong to tenant").build();
        }

        if (device.status != POSDevice.DeviceStatus.ACTIVE) {
            return Response.status(Response.Status.CONFLICT).entity("Device not active").build();
        }

        // Update device heartbeat
        deviceService.updateHeartbeat(device.id, request.firmwareVersion);

        int enqueued = 0;
        int duplicates = 0;
        User currentUser = resolveCurrentUser();

        for (OfflineTransactionUpload tx : request.transactions) {
            if (tx.localTransactionId == null || tx.localTransactionId.isBlank()) {
                LOG.warn("Skipping transaction without localTransactionId");
                continue;
            }

            String expectedIdempotencyKey = POSOfflineQueue.generateIdempotencyKey(device.tenant.id, device.id,
                    tx.localTransactionId);

            POSOfflineTransaction existingTransaction = POSOfflineTransaction
                    .find("device = ?1 AND localTransactionId = ?2", device, tx.localTransactionId).firstResult();
            if (existingTransaction != null) {
                LOG.warnf("Transaction %s already synced (payment_intent=%s)", tx.localTransactionId,
                        existingTransaction.paymentIntentId);
                duplicates++;
                continue;
            }

            // Check for duplicate
            POSOfflineQueue existing = POSOfflineQueue.find("idempotencyKey = ?1", expectedIdempotencyKey)
                    .firstResult();
            if (existing != null) {
                LOG.warnf("Duplicate offline transaction: %s (skipping)", tx.localTransactionId);
                duplicates++;
                continue;
            }

            byte[] payloadBytes;
            byte[] ivBytes;
            try {
                payloadBytes = Base64.getDecoder().decode(tx.encryptedPayload);
                ivBytes = Base64.getDecoder().decode(tx.encryptionIv);
            } catch (IllegalArgumentException ex) {
                LOG.warnf("Invalid base64 payload for transaction %s", tx.localTransactionId);
                continue;
            }

            OffsetDateTime occurredAt;
            try {
                occurredAt = OffsetDateTime.parse(tx.transactionTimestamp);
            } catch (Exception e) {
                LOG.warnf("Invalid timestamp for transaction %s: %s", tx.localTransactionId, tx.transactionTimestamp);
                continue;
            }

            // Create queue entry
            POSOfflineQueue queueEntry = new POSOfflineQueue();
            queueEntry.tenant = device.tenant;
            queueEntry.device = device;
            queueEntry.encryptedPayload = payloadBytes;
            queueEntry.encryptionKeyVersion = tx.encryptionKeyVersion;
            queueEntry.encryptionIv = ivBytes;
            queueEntry.localTransactionId = tx.localTransactionId;
            queueEntry.transactionTimestamp = occurredAt;
            queueEntry.transactionAmount = tx.transactionAmount;
            queueEntry.syncPriority = resolvePriority(tx.priority);
            queueEntry.idempotencyKey = expectedIdempotencyKey;
            queueEntry.staffUser = currentUser;
            queueEntry.persist();

            // Enqueue for processing
            syncProcessor.enqueueSync(queueEntry);
            enqueued++;
        }

        meterRegistry.counter("pos.offline.upload.received", "tenant", tenantId.toString())
                .increment(request.transactions.size());

        LOG.infof("Enqueued %d offline transactions (duplicates=%d) for device %d", enqueued, duplicates, device.id);
        return Response.ok(new BatchUploadResponse(enqueued, duplicates)).build();
    }

    /**
     * Get offline queue status for a device.
     */
    @GET
    @Path("/offline/status/{deviceId}")
    @Operation(
            summary = "Get offline sync status",
            description = "Check sync queue status for device")
    public Response getSyncStatus(@PathParam("deviceId") Long deviceId) {
        long queuedCount = POSOfflineQueue.countQueuedByDevice(deviceId);
        List<POSOfflineQueue> processingEntries = POSOfflineQueue.findByDeviceAndStatus(deviceId,
                POSOfflineQueue.SyncStatus.PROCESSING);
        List<POSOfflineQueue> failedEntries = POSOfflineQueue.findByDeviceAndStatus(deviceId,
                POSOfflineQueue.SyncStatus.FAILED);

        return Response.ok(new SyncStatusResponse(queuedCount, processingEntries.size(), failedEntries.size())).build();
    }

    /**
     * Issue a new Stripe Terminal connection token for an active device.
     */
    @POST
    @Path("/devices/{deviceId}/terminal/token")
    @Operation(
            summary = "Create Stripe Terminal connection token",
            description = "Request a new connection token for an active POS device")
    public Response createTerminalConnectionToken(@PathParam("deviceId") Long deviceId) {
        POSDevice device = POSDevice.findById(deviceId);
        if (device == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Device not found").build();
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null || !tenantId.equals(device.tenant.id)) {
            return Response.status(Response.Status.FORBIDDEN).entity("Device does not belong to tenant").build();
        }

        String token = deviceService.issueTerminalConnectionToken(deviceId);
        return Response.ok(new TerminalTokenResponse(token)).build();
    }

    private SyncPriority resolvePriority(String priority) {
        if (priority == null || priority.isBlank()) {
            return SyncPriority.HIGH;
        }
        try {
            return SyncPriority.valueOf(priority.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warnf("Unknown sync priority '%s', falling back to HIGH", priority);
            return SyncPriority.HIGH;
        }
    }

    private User resolveCurrentUser() {
        if (securityIdentity == null || securityIdentity.getPrincipal() == null) {
            return null;
        }
        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null) {
            return null;
        }
        String email = securityIdentity.getPrincipal().getName();
        return User.find("tenant.id = ?1 AND email = ?2", tenantId, email).firstResult();
    }

    // DTO classes
    public record DevicePairingRequest(String deviceIdentifier, String deviceName, String locationName,
            String hardwareModel) {
    }

    public record DevicePairingResponse(Long deviceId, String deviceName, String pairingCode, String expiresAt) {
    }

    public record CompletePairingRequest(String pairingCode) {
    }

    public record PairingCompletionResponse(Long deviceId, String deviceName, String encryptionKey,
            int encryptionKeyVersion, String stripeConnectionToken) {
    }

    public record DeviceDto(Long id, String deviceName, String locationName, String hardwareModel,
            String firmwareVersion, String status, String lastSeenAt, String lastSyncedAt) {
    }

    public record OfflineBatchUploadRequest(Long deviceId, String firmwareVersion,
            List<OfflineTransactionUpload> transactions) {
    }

    public record OfflineTransactionUpload(String localTransactionId, String encryptedPayload, String encryptionIv,
            int encryptionKeyVersion, String transactionTimestamp, java.math.BigDecimal transactionAmount,
            String idempotencyKey, String priority) {
    }

    public record BatchUploadResponse(int enqueued, int duplicates) {
    }

    public record SyncStatusResponse(long queuedCount, long processingCount, long failedCount) {
    }

    public record TerminalTokenResponse(String connectionToken) {
    }
}
