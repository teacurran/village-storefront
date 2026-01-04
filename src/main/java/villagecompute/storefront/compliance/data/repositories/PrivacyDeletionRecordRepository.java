package villagecompute.storefront.compliance.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.compliance.data.models.PrivacyDeletionRecord;
import villagecompute.storefront.compliance.data.models.PrivacyDeletionRecord.DeletionStatus;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;

/**
 * Repository helpers for {@link PrivacyDeletionRecord} scheduling metadata.
 *
 * <p>
 * Operates outside {@link villagecompute.storefront.tenant.TenantContext} because purge scheduling must evaluate all
 * tenants globally.
 */
@ApplicationScoped
public class PrivacyDeletionRecordRepository implements PanacheRepositoryBase<PrivacyDeletionRecord, UUID> {

    /**
     * Find the record that tracks purge metadata for the given request.
     */
    public PrivacyDeletionRecord findByRequestId(UUID requestId) {
        return find("privacyRequest.id", requestId).firstResult();
    }

    /**
     * List deletion records whose purge window has elapsed and whose purge job has not yet been enqueued.
     */
    public List<PrivacyDeletionRecord> findDuePurges(OffsetDateTime now) {
        return list("status = ?1 AND purgeAfter <= ?2 AND purgeJobId IS NULL", DeletionStatus.SOFT_DELETED, now);
    }
}
