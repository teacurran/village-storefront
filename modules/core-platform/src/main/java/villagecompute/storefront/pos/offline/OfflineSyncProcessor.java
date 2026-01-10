package villagecompute.storefront.pos.offline;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.payment.PaymentProvider;
import villagecompute.storefront.services.jobs.config.DeadLetterQueue;
import villagecompute.storefront.services.jobs.config.JobConfig;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.services.jobs.config.JobProcessor;
import villagecompute.storefront.services.jobs.config.PriorityJobQueue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;

/**
 * Processor for syncing POS offline queue entries.
 *
 * <p>
 * Decrypts encrypted payloads, validates idempotency, and replays transactions through the checkout flow using
 * PaymentProvider. Handles retry logic and dead-letter queue for failed syncs.
 *
 * <p>
 * References:
 * <ul>
 * <li>Architecture: §3.6 Background Processing (offline POS batch uploads)</li>
 * <li>Code: JobProcessor pattern for retry/DLQ handling</li>
 * <li>Task I4.T7: Offline sync job implementation</li>
 * </ul>
 */
@ApplicationScoped
public class OfflineSyncProcessor {

    private static final Logger LOG = Logger.getLogger(OfflineSyncProcessor.class);
    private static final String PROCESSOR_NAME = "pos.offline_sync";
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    PaymentProvider paymentProvider;

    @Inject
    POSDeviceService deviceService;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    POSOfflineKeyService keyService;

    private PriorityJobQueue<OfflineSyncJobPayload> syncQueue;
    private DeadLetterQueue<OfflineSyncJobPayload> syncDlq;
    private JobProcessor<OfflineSyncJobPayload> jobProcessor;
    private JobConfig jobConfig;

    @jakarta.annotation.PostConstruct
    void initialize() {
        this.jobConfig = buildJobConfig();
        this.syncQueue = new PriorityJobQueue<>(PROCESSOR_NAME, meterRegistry, jobConfig);
        this.syncDlq = new DeadLetterQueue<>(PROCESSOR_NAME, meterRegistry);
        this.jobProcessor = new JobProcessor<>(PROCESSOR_NAME, meterRegistry, syncQueue, syncDlq, jobConfig,
                this::processQueueEntry, OfflineSyncJobPayload::tenantId);
        LOG.info("POS offline sync processor initialized");
    }

    private JobConfig buildJobConfig() {
        return JobConfig.builder().queueCapacity(JobPriority.CRITICAL, 300).queueCapacity(JobPriority.HIGH, 2000)
                .queueCapacity(JobPriority.DEFAULT, 5000)
                .retryPolicy(JobPriority.CRITICAL, JobConfig.defaults().getRetryPolicy(JobPriority.CRITICAL))
                .retryPolicy(JobPriority.HIGH, JobConfig.defaults().getRetryPolicy(JobPriority.HIGH))
                .retryPolicy(JobPriority.DEFAULT, JobConfig.defaults().getRetryPolicy(JobPriority.DEFAULT)).build();
    }

    /**
     * Enqueue an offline transaction for sync.
     */
    @Transactional
    public void enqueueSync(POSOfflineQueue queueEntry) {
        OfflineSyncJobPayload payload = OfflineSyncJobPayload.fromQueueEntry(queueEntry);
        boolean enqueued = syncQueue.enqueue(payload, queueEntry.toJobPriority());

        if (enqueued) {
            LOG.infof("Enqueued offline sync job for queue entry %d (device=%d)", queueEntry.id, queueEntry.device.id);
            meterRegistry
                    .counter(PROCESSOR_NAME + ".job.enqueued", "priority", queueEntry.toJobPriority().toMetricTag())
                    .increment();
        } else {
            LOG.errorf("Failed to enqueue offline sync job for queue entry %d (queue full)", queueEntry.id);
            meterRegistry.counter(PROCESSOR_NAME + ".queue.overflow").increment();
        }
    }

    /**
     * Process a single queue entry (called by JobProcessor).
     */
    @Transactional
    void processQueueEntry(OfflineSyncJobPayload payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            LOG.infof("Processing offline sync job: queueEntryId=%d, deviceId=%d", payload.queueEntryId(),
                    payload.deviceId());

            // Load queue entry
            POSOfflineQueue queueEntry = POSOfflineQueue.findById(payload.queueEntryId());
            if (queueEntry == null) {
                LOG.warnf("Queue entry not found: %d (already processed?)", payload.queueEntryId());
                return;
            }

            // Check if already completed
            if (queueEntry.syncStatus == POSOfflineQueue.SyncStatus.COMPLETED) {
                LOG.infof("Queue entry %d already completed, skipping", queueEntry.id);
                return;
            }

            // Mark as processing
            queueEntry.syncStatus = POSOfflineQueue.SyncStatus.PROCESSING;
            queueEntry.syncStartedAt = OffsetDateTime.now();
            queueEntry.syncAttemptCount++;
            queueEntry.persist();
            POSActivityLog.log(queueEntry.device, POSActivityLog.ActivityType.SYNC_STARTED, queueEntry.staffUser,
                    java.util.Map.of("queue_entry_id", queueEntry.id));

            // Decrypt payload
            String decryptedJson = decryptPayload(queueEntry);
            OfflineTransactionPayload txPayload = objectMapper.readValue(decryptedJson,
                    OfflineTransactionPayload.class);

            LOG.infof("Decrypted offline transaction: localTxId=%s, amount=%s", txPayload.localTransactionId,
                    txPayload.totalAmount);

            // TODO: Call checkout service to create order + capture payment
            // For now, simulate payment provider call
            PaymentProvider.CreatePaymentIntentRequest paymentRequest = new PaymentProvider.CreatePaymentIntentRequest(
                    txPayload.totalAmount, txPayload.currency, txPayload.customerId, txPayload.paymentMethodId, true, // capture
                                                                                                                      // immediately
                    java.util.Map.of("offline_tx_id", txPayload.localTransactionId, "device_id",
                            String.valueOf(payload.deviceId())),
                    queueEntry.idempotencyKey);

            PaymentProvider.PaymentIntentResult paymentResult = paymentProvider.createIntent(paymentRequest);

            // Create audit record
            POSOfflineTransaction auditRecord = new POSOfflineTransaction();
            auditRecord.tenant = queueEntry.tenant;
            auditRecord.device = queueEntry.device;
            auditRecord.queueEntry = queueEntry;
            auditRecord.localTransactionId = txPayload.localTransactionId;
            auditRecord.staffUser = queueEntry.staffUser;
            auditRecord.offlineTimestamp = queueEntry.transactionTimestamp;
            auditRecord.paymentIntentId = paymentResult.paymentIntentId();
            auditRecord.totalAmount = txPayload.totalAmount;
            auditRecord.syncedAt = OffsetDateTime.now();
            auditRecord.syncDurationMs = (int) sample
                    .stop(meterRegistry.timer(PROCESSOR_NAME + ".job.duration", "status", "success"));
            auditRecord.persist();

            // Mark queue entry as completed
            queueEntry.syncStatus = POSOfflineQueue.SyncStatus.COMPLETED;
            queueEntry.syncCompletedAt = OffsetDateTime.now();
            queueEntry.persist();

            // Update device sync timestamp
            deviceService.markSyncCompleted(payload.deviceId());

            LOG.infof("Successfully synced offline transaction %s (payment_intent=%s)", txPayload.localTransactionId,
                    paymentResult.paymentIntentId());
            meterRegistry.counter(PROCESSOR_NAME + ".sync.success").increment();

            POSActivityLog.log(queueEntry.device, POSActivityLog.ActivityType.SYNC_COMPLETED, queueEntry.staffUser,
                    java.util.Map.of("queue_entry_id", queueEntry.id, "payment_intent_id",
                            paymentResult.paymentIntentId()));

        } catch (Exception e) {
            LOG.errorf(e, "Failed to process offline sync job: queueEntryId=%d", payload.queueEntryId());

            // Update queue entry with error
            POSOfflineQueue queueEntry = POSOfflineQueue.findById(payload.queueEntryId());
            if (queueEntry != null) {
                int maxAttempts = jobConfig.getRetryPolicy(queueEntry.toJobPriority()).getMaxAttempts();
                if (maxAttempts > 0 && queueEntry.syncAttemptCount >= maxAttempts) {
                    queueEntry.syncStatus = POSOfflineQueue.SyncStatus.FAILED;
                } else {
                    queueEntry.syncStatus = POSOfflineQueue.SyncStatus.QUEUED; // Will retry
                }
                queueEntry.lastSyncError = e.getMessage();
                queueEntry.persist();

                POSActivityLog.log(queueEntry.device, POSActivityLog.ActivityType.SYNC_FAILED, queueEntry.staffUser,
                        java.util.Map.of("queue_entry_id", queueEntry.id, "error", e.getClass().getSimpleName()));
            }

            meterRegistry.counter(PROCESSOR_NAME + ".sync.failed", "error_type", e.getClass().getSimpleName())
                    .increment();
            throw e; // Let JobProcessor handle retry
        }
    }

    /**
     * Scheduled dispatcher invoked periodically to drain the offline queue.
     */
    @Scheduled(
            every = "{pos.offline.sync.dispatch-interval:5s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            identity = "pos-offline-sync-dispatcher")
    void dispatchQueuedJobs() {
        int processed = 0;
        while (jobProcessor.processNext()) {
            processed++;
            if (processed >= 50) {
                break;
            }
        }
        if (processed > 0) {
            LOG.debugf("POS offline sync dispatcher processed %d entries", processed);
        }
    }

    /**
     * Visible for tests/schedulers needing explicit draining.
     */
    boolean processNextJob() {
        return jobProcessor.processNext();
    }

    /**
     * Decrypt AES-GCM encrypted payload.
     */
    private String decryptPayload(POSOfflineQueue queueEntry) throws Exception {
        byte[] encryptionKey = retrieveEncryptionKey(queueEntry.device, queueEntry.encryptionKeyVersion);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, queueEntry.encryptionIv);
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        byte[] decryptedBytes = cipher.doFinal(queueEntry.encryptedPayload);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }

    /**
     * Retrieve encryption key for device (placeholder - needs secure key management).
     */
    private byte[] retrieveEncryptionKey(POSDevice device, int version) {
        POSDeviceKey keyRecord = POSDeviceKey.findByDeviceAndVersion(device.id, version);
        if (keyRecord == null) {
            throw new IllegalStateException("Missing encryption key for device " + device.id + " version " + version);
        }
        return keyService.decryptDeviceKey(keyRecord.keyCiphertext);
    }

    /**
     * Decrypted offline transaction payload structure.
     */
    public record OfflineTransactionPayload(String localTransactionId, java.math.BigDecimal totalAmount,
            String currency, String customerId, String paymentMethodId, java.util.List<CartItem> items) {

        public record CartItem(String productId, String variantId, int quantity, java.math.BigDecimal price) {
        }
    }
}
