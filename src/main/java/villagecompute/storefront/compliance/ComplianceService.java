package villagecompute.storefront.compliance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;

import villagecompute.storefront.compliance.data.models.MarketingConsent;
import villagecompute.storefront.compliance.data.models.PrivacyDeletionRecord;
import villagecompute.storefront.compliance.data.models.PrivacyRequest;
import villagecompute.storefront.compliance.data.models.PrivacyRequest.RequestStatus;
import villagecompute.storefront.compliance.data.repositories.MarketingConsentRepository;
import villagecompute.storefront.compliance.data.repositories.PrivacyDeletionRecordRepository;
import villagecompute.storefront.compliance.data.repositories.PrivacyRequestRepository;
import villagecompute.storefront.compliance.jobs.PrivacyDeleteJobPayload;
import villagecompute.storefront.compliance.jobs.PrivacyExportJobPayload;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.data.repositories.UserRepository;
import villagecompute.storefront.platformops.data.models.PlatformCommand;
import villagecompute.storefront.reporting.ReportStorageClient;
import villagecompute.storefront.services.jobs.config.DeadLetterQueue;
import villagecompute.storefront.services.jobs.config.JobConfig;
import villagecompute.storefront.services.jobs.config.JobPriority;
import villagecompute.storefront.services.jobs.config.JobProcessor;
import villagecompute.storefront.services.jobs.config.PriorityJobQueue;
import villagecompute.storefront.services.jobs.config.RetryPolicy;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;

/**
 * Compliance automation service for privacy exports, deletions, and consent management.
 *
 * <p>
 * Orchestrates GDPR/CCPA workflows:
 * <ul>
 * <li>Privacy exports: Generates zipped JSONL + CSV data exports stored in R2 with signed URLs</li>
 * <li>Privacy deletions: Two-phase soft-delete then purge after retention period</li>
 * <li>Consent management: Tracks marketing consent for compliance audits</li>
 * <li>Audit logging: All actions recorded in PlatformCommand for traceability</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task: I5.T6 - Compliance automation</li>
 * <li>Architecture: 01_Blueprint_Foundation.md Section 5 (Data Governance)</li>
 * <li>Architecture: 04_Operational_Architecture.md Section 3.15 (Compliance personas)</li>
 * </ul>
 */
@ApplicationScoped
public class ComplianceService {

    private static final Logger LOG = Logger.getLogger(ComplianceService.class);
    private static final Duration DEFAULT_SIGNED_URL_EXPIRY = Duration.ofHours(72);

    @Inject
    PrivacyRequestRepository privacyRequestRepo;

    @Inject
    MarketingConsentRepository consentRepo;

    @Inject
    UserRepository userRepository;

    @Inject
    PrivacyDeletionRecordRepository deletionRecordRepo;

    @Inject
    ReportStorageClient storageClient;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(
            name = "compliance.delete.retention_days",
            defaultValue = "90")
    int deleteRetentionDays;

    @ConfigProperty(
            name = "jobs.queue.capacity.high",
            defaultValue = "5000")
    int highQueueCapacity;

    @ConfigProperty(
            name = "jobs.retry.max_attempts.high",
            defaultValue = "3")
    int highRetryAttempts;

    private JobConfig jobConfig;
    private PriorityJobQueue<PrivacyExportJobPayload> exportQueue;
    private DeadLetterQueue<PrivacyExportJobPayload> exportDlq;
    private JobProcessor<PrivacyExportJobPayload> exportProcessor;

    private PriorityJobQueue<PrivacyDeleteJobPayload> deleteQueue;
    private DeadLetterQueue<PrivacyDeleteJobPayload> deleteDlq;
    private JobProcessor<PrivacyDeleteJobPayload> deleteProcessor;

    @PostConstruct
    void initializeJobFramework() {
        jobConfig = buildJobConfig();

        exportQueue = new PriorityJobQueue<>("compliance.export", meterRegistry, jobConfig);
        exportDlq = new DeadLetterQueue<>("compliance.export", meterRegistry);
        exportProcessor = new JobProcessor<>("compliance.export", meterRegistry, exportQueue, exportDlq, jobConfig,
                this::handleExportJob, PrivacyExportJobPayload::getTenantId);

        deleteQueue = new PriorityJobQueue<>("compliance.delete", meterRegistry, jobConfig);
        deleteDlq = new DeadLetterQueue<>("compliance.delete", meterRegistry);
        deleteProcessor = new JobProcessor<>("compliance.delete", meterRegistry, deleteQueue, deleteDlq, jobConfig,
                this::handleDeleteJob, PrivacyDeleteJobPayload::getTenantId);
    }

    /**
     * Submit a privacy export request (queues for approval workflow).
     *
     * @param requesterEmail
     *            email of person requesting export
     * @param subjectEmail
     *            email of data subject
     * @param reason
     *            reason for export
     * @param ticketNumber
     *            support ticket reference
     * @return privacy request ID
     */
    @Transactional
    public UUID submitExportRequest(String requesterEmail, String subjectEmail, String reason, String ticketNumber) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Tenant tenant = Tenant.findById(tenantId);

        PrivacyRequest request = new PrivacyRequest();
        request.tenant = tenant;
        request.requestType = PrivacyRequest.RequestType.EXPORT;
        request.status = RequestStatus.PENDING_REVIEW;
        request.requesterEmail = requesterEmail;
        request.subjectEmail = subjectEmail;
        request.subjectIdentifierHash = hashIdentifier(subjectEmail);
        request.reason = reason;
        request.ticketNumber = ticketNumber;

        privacyRequestRepo.persist(request);

        recordSubmissionAudit(request, requesterEmail, "privacy_export_requested");

        LOG.infof("Privacy export request submitted - requestId=%s, tenantId=%s, subject=%s", request.id, tenantId,
                subjectEmail);

        meterRegistry.counter("compliance.export.requested", "tenant_id", tenantId.toString()).increment();

        return request.id;
    }

    /**
     * Approve and enqueue export request for processing.
     *
     * @param requestId
     *            privacy request ID
     * @param approverEmail
     *            email of approver
     * @param approvalNotes
     *            approval notes
     * @return job ID
     */
    @Transactional
    public UUID approveExportRequest(UUID requestId, String approverEmail, String approvalNotes) {
        PrivacyRequest request = PrivacyRequest.findById(requestId);
        if (request == null) {
            throw new IllegalArgumentException("Privacy request not found: " + requestId);
        }

        if (request.status != RequestStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Request not pending review: " + requestId);
        }

        request.status = RequestStatus.APPROVED;
        request.approvedByEmail = approverEmail;
        request.approvedAt = OffsetDateTime.now();
        request.approvalNotes = approvalNotes;

        // Create audit log entry
        PlatformCommand auditCmd = new PlatformCommand();
        auditCmd.actorType = "platform_admin";
        auditCmd.actorEmail = approverEmail;
        auditCmd.action = "approve_privacy_export";
        auditCmd.targetType = "privacy_request";
        auditCmd.targetId = requestId;
        auditCmd.reason = approvalNotes;
        auditCmd.metadata = String.format("{\"tenant_id\":\"%s\",\"subject_email\":\"%s\"}", request.tenant.id,
                request.subjectEmail);
        auditCmd.persist();

        request.platformCommand = auditCmd;
        request.persist();

        // Enqueue export job
        PrivacyExportJobPayload payload = PrivacyExportJobPayload.create(request.tenant.id, requestId,
                request.subjectIdentifierHash, approverEmail);

        boolean enqueued = exportQueue.enqueue(payload, JobPriority.HIGH);
        if (!enqueued) {
            throw new IllegalStateException("Export queue capacity reached");
        }

        LOG.infof("Privacy export approved and enqueued - requestId=%s, jobId=%s", requestId, payload.getJobId());

        meterRegistry.counter("compliance.export.approved", "tenant_id", request.tenant.id.toString()).increment();

        return payload.getJobId();
    }

    /**
     * Submit a privacy delete request (queues for approval workflow).
     *
     * @param requesterEmail
     *            email of person requesting deletion
     * @param subjectEmail
     *            email of data subject
     * @param reason
     *            reason for deletion
     * @param ticketNumber
     *            support ticket reference
     * @return privacy request ID
     */
    @Transactional
    public UUID submitDeleteRequest(String requesterEmail, String subjectEmail, String reason, String ticketNumber) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        Tenant tenant = Tenant.findById(tenantId);

        PrivacyRequest request = new PrivacyRequest();
        request.tenant = tenant;
        request.requestType = PrivacyRequest.RequestType.DELETE;
        request.status = RequestStatus.PENDING_REVIEW;
        request.requesterEmail = requesterEmail;
        request.subjectEmail = subjectEmail;
        request.subjectIdentifierHash = hashIdentifier(subjectEmail);
        request.reason = reason;
        request.ticketNumber = ticketNumber;

        privacyRequestRepo.persist(request);

        recordSubmissionAudit(request, requesterEmail, "privacy_delete_requested");

        LOG.infof("Privacy delete request submitted - requestId=%s, tenantId=%s, subject=%s", request.id, tenantId,
                subjectEmail);

        meterRegistry.counter("compliance.delete.requested", "tenant_id", tenantId.toString()).increment();

        return request.id;
    }

    /**
     * Approve and enqueue delete request for processing.
     *
     * @param requestId
     *            privacy request ID
     * @param approverEmail
     *            email of approver
     * @param approvalNotes
     *            approval notes
     * @return job ID
     */
    @Transactional
    public UUID approveDeleteRequest(UUID requestId, String approverEmail, String approvalNotes) {
        PrivacyRequest request = PrivacyRequest.findById(requestId);
        if (request == null) {
            throw new IllegalArgumentException("Privacy request not found: " + requestId);
        }

        if (request.status != RequestStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Request not pending review: " + requestId);
        }

        request.status = RequestStatus.APPROVED;
        request.approvedByEmail = approverEmail;
        request.approvedAt = OffsetDateTime.now();
        request.approvalNotes = approvalNotes;

        // Create audit log entry
        PlatformCommand auditCmd = new PlatformCommand();
        auditCmd.actorType = "platform_admin";
        auditCmd.actorEmail = approverEmail;
        auditCmd.action = "approve_privacy_delete";
        auditCmd.targetType = "privacy_request";
        auditCmd.targetId = requestId;
        auditCmd.reason = approvalNotes;
        auditCmd.metadata = String.format("{\"tenant_id\":\"%s\",\"subject_email\":\"%s\"}", request.tenant.id,
                request.subjectEmail);
        auditCmd.persist();

        request.platformCommand = auditCmd;
        request.persist();

        // Enqueue soft-delete job
        PrivacyDeleteJobPayload payload = PrivacyDeleteJobPayload.createSoftDelete(request.tenant.id, requestId,
                request.subjectIdentifierHash, approverEmail);

        boolean enqueued = deleteQueue.enqueue(payload, JobPriority.HIGH);
        if (!enqueued) {
            throw new IllegalStateException("Delete queue capacity reached");
        }

        LOG.infof("Privacy delete approved and enqueued - requestId=%s, jobId=%s", requestId, payload.getJobId());

        meterRegistry.counter("compliance.delete.approved", "tenant_id", request.tenant.id.toString()).increment();

        return payload.getJobId();
    }

    /**
     * Process next export job from queue.
     *
     * @return true if job was processed
     */
    @Transactional
    public boolean processNextExportJob() {
        return exportProcessor.processNext();
    }

    /**
     * Process next delete job from queue.
     *
     * @return true if job was processed
     */
    @Transactional
    public boolean processNextDeleteJob() {
        return deleteProcessor.processNext();
    }

    /**
     * Get export queue depth (for monitoring).
     *
     * @return queue depth
     */
    public int getExportQueueDepth() {
        return exportQueue != null ? exportQueue.getTotalDepth() : 0;
    }

    /**
     * Get delete queue depth (for monitoring).
     *
     * @return queue depth
     */
    public int getDeleteQueueDepth() {
        return deleteQueue != null ? deleteQueue.getTotalDepth() : 0;
    }

    @Scheduled(
            every = "10m",
            delayed = "30s",
            identity = "compliance-purge-scheduler")
    void scheduledPurgeEnqueue() {
        enqueueDuePurgeJobs();
    }

    /**
     * Enqueue purge jobs whose retention window has elapsed. Exposed for integration tests.
     *
     * @return number of purge jobs enqueued
     */
    @Transactional
    public int enqueueDuePurgeJobs() {
        if (deleteQueue == null) {
            LOG.warn("Delete queue not initialized; skipping purge scheduling");
            return 0;
        }
        List<PrivacyDeletionRecord> dueRecords = deletionRecordRepo.findDuePurges(OffsetDateTime.now());
        int enqueued = 0;

        for (PrivacyDeletionRecord record : dueRecords) {
            PrivacyDeleteJobPayload payload = PrivacyDeleteJobPayload.createPurge(record.tenant.id,
                    record.privacyRequest.id, record.subjectIdentifierHash, "system");

            boolean queued = deleteQueue.enqueue(payload, JobPriority.HIGH);
            if (queued) {
                record.purgeJobId = payload.getJobId();
                record.purgeJobEnqueuedAt = OffsetDateTime.now();
                record.status = PrivacyDeletionRecord.DeletionStatus.PURGE_QUEUED;

                meterRegistry.counter("compliance.delete.purge_enqueued", "tenant_id", record.tenant.id.toString())
                        .increment();
                enqueued++;
            } else {
                LOG.warnf("Delete queue capacity reached while enqueueing purge job - requestId=%s",
                        record.privacyRequest.id);
            }
        }

        return enqueued;
    }

    // --- Private Helpers ---

    private void handleExportJob(PrivacyExportJobPayload payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        LOG.infof("Processing privacy export job - jobId=%s, requestId=%s", payload.getJobId(),
                payload.getPrivacyRequestId());

        meterRegistry.counter("compliance.export.started", "tenant_id", payload.getTenantId().toString()).increment();

        PrivacyRequest request = PrivacyRequest.findById(payload.getPrivacyRequestId());
        if (request == null) {
            throw new IllegalStateException("Privacy request not found: " + payload.getPrivacyRequestId());
        }

        request.status = RequestStatus.IN_PROGRESS;
        request.persist();

        try {
            // Generate export data
            byte[] exportZip = generatePrivacyExport(request);

            // Compute hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(exportZip);
            String hashHex = HexFormat.of().formatHex(hash);

            // Upload to storage
            String objectKey = String.format("%s/privacy-exports/%s-%s.zip", payload.getTenantId(),
                    request.subjectEmail.replaceAll("[^a-zA-Z0-9]", "_"), hashHex.substring(0, 8));

            storageClient.uploadReport(objectKey, new ByteArrayInputStream(exportZip), "application/zip",
                    exportZip.length);

            String signedUrl = storageClient.getSignedDownloadUrl(objectKey, DEFAULT_SIGNED_URL_EXPIRY);

            request.status = RequestStatus.COMPLETED;
            request.resultUrl = signedUrl;
            request.completedAt = OffsetDateTime.now();
            request.persist();

            LOG.infof("Privacy export completed - jobId=%s, requestId=%s, size=%d bytes", payload.getJobId(),
                    payload.getPrivacyRequestId(), exportZip.length);

            sample.stop(
                    meterRegistry.timer("compliance.export.duration", "tenant_id", payload.getTenantId().toString()));
        } catch (Exception e) {
            LOG.errorf(e, "Privacy export failed - jobId=%s, requestId=%s", payload.getJobId(),
                    payload.getPrivacyRequestId());

            request.status = RequestStatus.FAILED;
            request.errorMessage = e.getMessage();
            request.completedAt = OffsetDateTime.now();
            request.persist();

            meterRegistry.counter("compliance.export.failed", "tenant_id", payload.getTenantId().toString())
                    .increment();
            throw e;
        }
    }

    private void handleDeleteJob(PrivacyDeleteJobPayload payload) throws Exception {
        Timer.Sample sample = Timer.start(meterRegistry);

        LOG.infof("Processing privacy delete job - jobId=%s, requestId=%s, purge=%s", payload.getJobId(),
                payload.getPrivacyRequestId(), payload.isPurge());

        meterRegistry.counter("compliance.delete.started", "tenant_id", payload.getTenantId().toString(), "phase",
                payload.isPurge() ? "purge" : "soft_delete").increment();

        PrivacyRequest request = PrivacyRequest.findById(payload.getPrivacyRequestId());
        if (request == null) {
            throw new IllegalStateException("Privacy request not found: " + payload.getPrivacyRequestId());
        }

        request.status = RequestStatus.IN_PROGRESS;
        request.persist();

        try {
            if (payload.isPurge()) {
                executePurge(request);
                request.status = RequestStatus.COMPLETED;
                request.completedAt = OffsetDateTime.now();
            } else {
                executeSoftDelete(request);
                schedulePurgeJob(request, payload.getRequestedBy());
                request.status = RequestStatus.AWAITING_PURGE;
                request.completedAt = null;
            }

            request.persist();

            LOG.infof("Privacy delete completed - jobId=%s, requestId=%s, purge=%s", payload.getJobId(),
                    payload.getPrivacyRequestId(), payload.isPurge());

            sample.stop(meterRegistry.timer("compliance.delete.duration", "tenant_id", payload.getTenantId().toString(),
                    "phase", payload.isPurge() ? "purge" : "soft_delete"));
        } catch (Exception e) {
            LOG.errorf(e, "Privacy delete failed - jobId=%s, requestId=%s", payload.getJobId(),
                    payload.getPrivacyRequestId());

            request.status = RequestStatus.FAILED;
            request.errorMessage = e.getMessage();
            request.completedAt = OffsetDateTime.now();
            request.persist();

            meterRegistry.counter("compliance.delete.failed", "tenant_id", payload.getTenantId().toString())
                    .increment();
            throw e;
        }
    }

    private byte[] generatePrivacyExport(PrivacyRequest request) throws Exception {
        ByteArrayOutputStream zipOutput = new ByteArrayOutputStream();

        try (ZipOutputStream zip = new ZipOutputStream(zipOutput)) {
            // Add customer data JSONL
            zip.putNextEntry(new ZipEntry("customer_data.jsonl"));
            User user = userRepository.findByTenantAndEmail(request.tenant.id, request.subjectEmail);
            if (user != null) {
                String customerJson = objectMapper.writeValueAsString(user);
                zip.write(customerJson.getBytes(StandardCharsets.UTF_8));
                zip.write('\n');
            }
            zip.closeEntry();

            // Add consent timeline CSV
            zip.putNextEntry(new ZipEntry("marketing_consents.csv"));
            ByteArrayOutputStream csvOutput = new ByteArrayOutputStream();
            try (OutputStreamWriter writer = new OutputStreamWriter(csvOutput, StandardCharsets.UTF_8);
                    CSVWriter csvWriter = new CSVWriter(writer)) {

                csvWriter.writeNext(new String[]{"Channel", "Consented", "Source", "Method", "Timestamp"});

                if (user != null) {
                    List<MarketingConsent> consents = consentRepo.findByCustomer(user.id);
                    for (MarketingConsent consent : consents) {
                        csvWriter.writeNext(new String[]{consent.channel, String.valueOf(consent.consented),
                                consent.consentSource, consent.consentMethod,
                                consent.consentedAt != null ? consent.consentedAt.toString() : ""});
                    }
                }
                csvWriter.flush();
            }
            zip.write(csvOutput.toByteArray());
            zip.closeEntry();

            // Add summary CSV
            zip.putNextEntry(new ZipEntry("export_summary.csv"));
            ByteArrayOutputStream summaryOutput = new ByteArrayOutputStream();
            try (OutputStreamWriter writer = new OutputStreamWriter(summaryOutput, StandardCharsets.UTF_8);
                    CSVWriter csvWriter = new CSVWriter(writer)) {

                csvWriter.writeNext(new String[]{"Field", "Value"});
                csvWriter.writeNext(new String[]{"Tenant", request.tenant.name});
                csvWriter.writeNext(new String[]{"Subject Email", request.subjectEmail});
                csvWriter.writeNext(new String[]{"Request Date", request.createdAt.toString()});
                csvWriter.writeNext(new String[]{"Export Date", OffsetDateTime.now().toString()});
                csvWriter.writeNext(new String[]{"Requester", request.requesterEmail});
                csvWriter.flush();
            }
            zip.write(summaryOutput.toByteArray());
            zip.closeEntry();

            zip.finish();
        }

        return zipOutput.toByteArray();
    }

    private void executeSoftDelete(PrivacyRequest request) {
        User user = userRepository.findByTenantAndEmail(request.tenant.id, request.subjectEmail);
        if (user == null) {
            LOG.warnf("User not found for soft delete - email=%s", request.subjectEmail);
            return;
        }

        LOG.infof("Soft-deleting user data - userId=%s, email=%s", user.id, user.email);
        user.status = "deleted";
        user.updatedAt = OffsetDateTime.now();

        // Create audit entry
        PlatformCommand auditCmd = new PlatformCommand();
        auditCmd.actorType = "system";
        auditCmd.action = "privacy_soft_delete";
        auditCmd.targetType = "user";
        auditCmd.targetId = user.id;
        auditCmd.metadata = String.format("{\"tenant_id\":\"%s\",\"privacy_request_id\":\"%s\"}", request.tenant.id,
                request.id);
        auditCmd.persist();

        PrivacyDeletionRecord record = ensureDeletionRecord(request);
        OffsetDateTime now = OffsetDateTime.now();
        record.softDeletedAt = now;
        record.purgeAfter = now.plusDays(Math.max(0, deleteRetentionDays));
        record.purgeJobId = null;
        record.purgeJobEnqueuedAt = null;
        record.status = PrivacyDeletionRecord.DeletionStatus.SOFT_DELETED;

        meterRegistry.counter("compliance.delete.soft_deleted", "tenant_id", request.tenant.id.toString()).increment();
    }

    private void executePurge(PrivacyRequest request) {
        // Find and permanently delete customer records
        User user = userRepository.findByTenantAndEmail(request.tenant.id, request.subjectEmail);
        if (user == null) {
            LOG.warnf("User not found for purge - email=%s", request.subjectEmail);
            return;
        }

        LOG.infof("Purging user data - userId=%s, email=%s", user.id, user.email);

        // Delete related records (consents, addresses, etc.)
        consentRepo.delete("user.id", user.id);

        // Delete user
        user.delete();

        // Create audit entry
        PlatformCommand auditCmd = new PlatformCommand();
        auditCmd.actorType = "system";
        auditCmd.action = "privacy_purge";
        auditCmd.targetType = "user";
        auditCmd.targetId = user.id;
        auditCmd.metadata = String.format("{\"tenant_id\":\"%s\",\"privacy_request_id\":\"%s\"}", request.tenant.id,
                request.id);
        auditCmd.persist();

        PrivacyDeletionRecord record = deletionRecordRepo.findByRequestId(request.id);
        if (record != null) {
            record.purgedAt = OffsetDateTime.now();
            record.status = PrivacyDeletionRecord.DeletionStatus.PURGED;
        }

        meterRegistry.counter("compliance.delete.purged", "tenant_id", request.tenant.id.toString()).increment();
    }

    private void schedulePurgeJob(PrivacyRequest request, String requestedBy) {
        PrivacyDeletionRecord record = ensureDeletionRecord(request);
        OffsetDateTime purgeAfter = record.purgeAfter != null ? record.purgeAfter
                : OffsetDateTime.now().plusDays(Math.max(0, deleteRetentionDays));
        record.purgeAfter = purgeAfter;
        record.purgeJobId = null;
        record.purgeJobEnqueuedAt = null;
        record.status = PrivacyDeletionRecord.DeletionStatus.SOFT_DELETED;

        LOG.infof("Purge scheduled for %s - requestId=%s, requestedBy=%s", purgeAfter, request.id, requestedBy);
    }

    private String hashIdentifier(String identifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(identifier.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash identifier", e);
        }
    }

    private void recordSubmissionAudit(PrivacyRequest request, String actorEmail, String action) {
        PlatformCommand auditCmd = new PlatformCommand();
        auditCmd.actorType = "platform_admin";
        auditCmd.actorEmail = actorEmail;
        auditCmd.action = action;
        auditCmd.targetType = "privacy_request";
        auditCmd.targetId = request.id;
        auditCmd.reason = request.reason;
        auditCmd.ticketNumber = request.ticketNumber;
        auditCmd.metadata = String.format("{\"tenant_id\":\"%s\",\"subject_identifier_hash\":\"%s\"}",
                request.tenant.id, request.subjectIdentifierHash);
        auditCmd.persist();
    }

    private PrivacyDeletionRecord ensureDeletionRecord(PrivacyRequest request) {
        PrivacyDeletionRecord record = deletionRecordRepo.findByRequestId(request.id);
        if (record == null) {
            record = new PrivacyDeletionRecord();
            record.tenant = request.tenant;
            record.privacyRequest = request;
            record.subjectIdentifierHash = request.subjectIdentifierHash;
            record.purgeAfter = OffsetDateTime.now().plusDays(Math.max(0, deleteRetentionDays));
            record.status = PrivacyDeletionRecord.DeletionStatus.SOFT_DELETED;
            record.persist();
        }
        return record;
    }

    private JobConfig buildJobConfig() {
        RetryPolicy highPolicy = RetryPolicy.builder().maxAttempts(highRetryAttempts)
                .initialDelay(Duration.ofSeconds(1)).maxDelay(Duration.ofMinutes(5)).backoffMultiplier(2.0)
                .exponentialBackoff(true).build();

        return JobConfig.builder().retryPolicy(JobPriority.HIGH, highPolicy)
                .queueCapacity(JobPriority.HIGH, highQueueCapacity).build();
    }
}
