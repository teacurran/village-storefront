package villagecompute.storefront.services;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.jobs.PayoutReconciliationJobHandler;
import villagecompute.storefront.jobs.PayoutReconciliationJobPayload;
import villagecompute.storefront.services.jobs.config.DeadLetterQueue;
import villagecompute.storefront.services.jobs.config.JobConfig;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.services.jobs.config.JobProcessor;
import villagecompute.storefront.services.jobs.config.PriorityJobQueue;
import villagecompute.storefront.services.jobs.config.RetryPolicy;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Coordinates payment-related asynchronous jobs (currently payout reconciliation). Provides queue management and
 * feature-flag-aware enqueue helpers so Stripe-specific logic stays encapsulated.
 */
@ApplicationScoped
public class PaymentJobService {

    private static final Logger LOGGER = Logger.getLogger(PaymentJobService.class);
    private static final String PAYOUT_QUEUE_NAME = "payments.payout.reconciliation";
    private static final String PAYOUT_FEATURE_FLAG = "payments.payout.reconciliation.enabled";

    @Inject
    PayoutReconciliationJobHandler payoutReconciliationJobHandler;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    FeatureToggle featureToggle;

    @ConfigProperty(
            name = "payments.payout.reconciliation.queue.capacity",
            defaultValue = "5000")
    int payoutQueueCapacity;

    @ConfigProperty(
            name = "payments.payout.reconciliation.retry.max-attempts",
            defaultValue = "5")
    int payoutRetryAttempts;

    @ConfigProperty(
            name = "payments.payout.reconciliation.retry.initial-delay",
            defaultValue = "PT5S")
    Duration payoutRetryInitialDelay;

    @ConfigProperty(
            name = "payments.payout.reconciliation.retry.max-delay",
            defaultValue = "PT10M")
    Duration payoutRetryMaxDelay;

    @ConfigProperty(
            name = "payments.payout.reconciliation.retry.backoff-multiplier",
            defaultValue = "2.0")
    double payoutRetryBackoff;

    private PriorityJobQueue<PayoutReconciliationJobPayload> payoutQueue;
    private DeadLetterQueue<PayoutReconciliationJobPayload> payoutDeadLetterQueue;
    private JobProcessor<PayoutReconciliationJobPayload> payoutProcessor;

    @PostConstruct
    void initializeQueues() {
        JobConfig jobConfig = JobConfig.builder()
                .retryPolicy(JobPriority.HIGH,
                        RetryPolicy.builder().maxAttempts(payoutRetryAttempts).initialDelay(payoutRetryInitialDelay)
                                .maxDelay(payoutRetryMaxDelay).backoffMultiplier(payoutRetryBackoff)
                                .exponentialBackoff(true).build())
                .queueCapacity(JobPriority.HIGH, payoutQueueCapacity).build();

        payoutQueue = new PriorityJobQueue<>(PAYOUT_QUEUE_NAME, meterRegistry, jobConfig);
        payoutDeadLetterQueue = new DeadLetterQueue<>(PAYOUT_QUEUE_NAME, meterRegistry);
        payoutProcessor = new JobProcessor<>(PAYOUT_QUEUE_NAME, meterRegistry, payoutQueue, payoutDeadLetterQueue,
                jobConfig, this::handlePayoutJob, PayoutReconciliationJobPayload::tenantId);
    }

    /**
     * Enqueue payout reconciliation job if the feature flag is enabled.
     *
     * @param payoutBatch
     *            payout batch entity
     * @param providerPayoutId
     *            Stripe payout identifier
     * @return job identifier if enqueued
     */
    public Optional<UUID> enqueuePayoutReconciliation(PayoutBatch payoutBatch, String providerPayoutId) {
        if (payoutBatch == null || providerPayoutId == null || providerPayoutId.isBlank()) {
            return Optional.empty();
        }

        if (payoutQueue == null) {
            LOGGER.warn("Payout queue not initialized; skipping enqueue");
            return Optional.empty();
        }

        UUID tenantId = payoutBatch.tenant != null ? payoutBatch.tenant.id : null;
        if (tenantId == null) {
            LOGGER.warn("Missing tenant on payout batch; unable to enqueue reconciliation job");
            return Optional.empty();
        }

        if (!featureToggle.isEnabled(tenantId, PAYOUT_FEATURE_FLAG)) {
            LOGGER.debugf("[Tenant: %s] Payout reconciliation disabled via feature flag", tenantId);
            return Optional.empty();
        }

        PayoutReconciliationJobPayload payload = PayoutReconciliationJobPayload.fromBatch(payoutBatch,
                providerPayoutId);

        boolean enqueued = payoutQueue.enqueue(payload, JobPriority.HIGH);
        if (!enqueued) {
            meterRegistry.counter("payments.payout.enqueue_rejected", "tenant", tenantId.toString()).increment();
            LOGGER.warnf("[Tenant: %s] Payout reconciliation queue full; job %s rejected", tenantId, payload.jobId());
            return Optional.empty();
        }

        meterRegistry.counter("payments.payout.enqueued", "tenant", tenantId.toString()).increment();
        LOGGER.infof("[Tenant: %s] Enqueued payout reconciliation job %s for batch %s", tenantId, payload.jobId(),
                payoutBatch.id);
        return Optional.of(payload.jobId());
    }

    /**
     * Process next payout reconciliation job (used by scheduler).
     *
     * @return true if a job was processed
     */
    public boolean processNextPayoutReconciliation() {
        return payoutProcessor != null && payoutProcessor.processNext();
    }

    private void handlePayoutJob(PayoutReconciliationJobPayload payload) throws Exception {
        payoutReconciliationJobHandler.handle(payload);
    }

    /**
     * Queue depth helper exposed for diagnostics/tests.
     */
    public int getPayoutQueueDepth() {
        return payoutQueue != null ? payoutQueue.getTotalDepth() : 0;
    }
}
