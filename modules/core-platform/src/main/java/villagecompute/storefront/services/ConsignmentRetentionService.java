package villagecompute.storefront.services;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.ConsignmentRetentionRecord;
import villagecompute.storefront.data.repositories.ConsignmentRetentionRepository;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Service for recording consignment-specific data retention hooks (archive/purge schedules).
 */
@ApplicationScoped
public class ConsignmentRetentionService {

    private static final Logger LOG = Logger.getLogger(ConsignmentRetentionService.class);

    @Inject
    ConsignmentRetentionRepository retentionRepository;

    @Inject
    MeterRegistry meterRegistry;

    /**
     * Register a retention hook for a consignment resource (e.g., intake batch) so that downstream jobs can archive or
     * purge it once the retention window expires.
     *
     * @param resourceType
     *            type identifier (INTAKE_BATCH, COMMISSION_DOC, etc.)
     * @param resourceId
     *            resource UUID
     * @param retainUntil
     *            timestamp when archival/purge can occur
     * @return created/updated retention record
     */
    @Transactional
    public ConsignmentRetentionRecord registerRetention(String resourceType, UUID resourceId,
            OffsetDateTime retainUntil) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Registering retention hook - tenantId=%s, type=%s, resourceId=%s, retainUntil=%s", tenantId,
                resourceType, resourceId, retainUntil);

        ConsignmentRetentionRecord record = retentionRepository.findByResource(resourceType, resourceId)
                .orElseGet(ConsignmentRetentionRecord::new);

        if (record.id == null) {
            record.resourceType = resourceType;
            record.resourceId = resourceId;
            record.tenant = villagecompute.storefront.data.models.Tenant.findById(tenantId);
        }
        record.retainUntil = retainUntil;
        record.status = "scheduled";

        retentionRepository.persist(record);
        meterRegistry.counter("consignment.retention.registered", "tenant_id", tenantId.toString(), "resource_type",
                resourceType).increment();

        return record;
    }

    /**
     * Update the status of a retention record once archival/purging occurs.
     */
    @Transactional
    public void markStatus(String resourceType, UUID resourceId, String status) {
        retentionRepository.findByResource(resourceType, resourceId).ifPresent(record -> {
            record.status = status;
            retentionRepository.persist(record);
            UUID tenantId = TenantContext.getCurrentTenantId();
            meterRegistry.counter("consignment.retention.status", "tenant_id", tenantId.toString(), "resource_type",
                    resourceType, "status", status).increment();
        });
    }
}
