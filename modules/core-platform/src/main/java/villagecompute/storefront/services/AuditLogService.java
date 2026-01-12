package villagecompute.storefront.services;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.AuditLogEntry;
import villagecompute.storefront.data.repositories.AuditLogRepository;
import villagecompute.storefront.tenant.TenantContext;

/**
 * Service for recording tenant-scoped audit log entries.
 */
@ApplicationScoped
public class AuditLogService {

    private static final Logger LOG = Logger.getLogger(AuditLogService.class);

    @Inject
    AuditLogRepository auditLogRepository;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Record an inventory action into the audit log.
     *
     * @param action
     *            action identifier (e.g., {@code inventory_transfer_created})
     * @param entityType
     *            entity type (e.g., {@code InventoryTransfer})
     * @param entityId
     *            entity identifier
     * @param changes
     *            context payload (will be serialized to JSON)
     * @return persisted entry
     */
    @Transactional
    public AuditLogEntry recordInventoryAction(String action, String entityType, UUID entityId,
            Map<String, Object> changes) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.action = action;
        entry.entityType = entityType;
        entry.entityId = entityId;
        entry.tenantId = TenantContext.hasContext() ? TenantContext.getCurrentTenantId() : null;
        entry.changes = serializeChanges(changes);

        auditLogRepository.persist(entry);

        LOG.infof("Audit log recorded - tenantId=%s, action=%s, entityType=%s, entityId=%s", entry.tenantId, action,
                entityType, entityId);

        return entry;
    }

    /**
     * Record an order-level audit entry.
     *
     * @param action
     *            audit action identifier
     * @param orderId
     *            order UUID
     * @param userId
     *            associated user (nullable)
     * @param changes
     *            context payload
     * @return persisted entry
     */
    @Transactional
    public AuditLogEntry recordOrderAction(String action, UUID orderId, UUID userId, Map<String, Object> changes) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.action = action;
        entry.entityType = "Order";
        entry.entityId = orderId;
        entry.userId = userId;
        entry.tenantId = TenantContext.hasContext() ? TenantContext.getCurrentTenantId() : null;
        entry.changes = serializeChanges(changes);

        auditLogRepository.persist(entry);

        LOG.infof("Order audit log recorded - tenantId=%s, orderId=%s, action=%s", entry.tenantId, orderId, action);

        return entry;
    }

    private String serializeChanges(Map<String, Object> changes) {
        try {
            Map<String, Object> safeChanges = changes != null ? changes : Collections.emptyMap();
            return objectMapper.writeValueAsString(safeChanges);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize audit log changes, falling back to empty payload", e);
            return "{}";
        }
    }
}
