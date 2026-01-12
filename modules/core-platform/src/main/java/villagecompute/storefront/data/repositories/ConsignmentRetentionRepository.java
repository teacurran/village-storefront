package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.ConsignmentRetentionRecord;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for consignment retention hooks scoped per tenant.
 */
@ApplicationScoped
public class ConsignmentRetentionRepository implements PanacheRepositoryBase<ConsignmentRetentionRecord, UUID> {

    private static final String QUERY_BY_RESOURCE = "tenant.id = :tenantId and resourceType = :resourceType and resourceId = :resourceId";

    public Optional<ConsignmentRetentionRecord> findByResource(String resourceType, UUID resourceId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_BY_RESOURCE,
                Parameters.with("tenantId", tenantId).and("resourceType", resourceType).and("resourceId", resourceId))
                .firstResultOptional();
    }

    public long deleteExpired(OffsetDateTime now) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return delete("tenant.id = ?1 and retainUntil <= ?2", tenantId, now);
    }
}
