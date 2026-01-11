package villagecompute.storefront.data.repositories;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.AuditLogEntry;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

/**
 * Repository for {@link AuditLogEntry}. Provides convenience lookup helpers for tests and services.
 */
@ApplicationScoped
public class AuditLogRepository implements PanacheRepositoryBase<AuditLogEntry, UUID> {

    public List<AuditLogEntry> findByAction(String action) {
        return list("action", action);
    }

    public List<AuditLogEntry> findByEntityAndAction(UUID entityId, String action) {
        return list("entityId = ?1 and action = ?2", entityId, action);
    }
}
