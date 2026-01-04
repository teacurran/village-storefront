package villagecompute.storefront.compliance.data.repositories;

import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.compliance.data.models.MarketingConsent;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for MarketingConsent entities with tenant-scoped queries.
 *
 * <p>
 * All queries automatically filter by current tenant context to prevent cross-tenant data leakage.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T6: Compliance automation (consent management)</li>
 * <li>ADR-001: Tenancy Strategy (Repository-Level Enforcement)</li>
 * </ul>
 */
@ApplicationScoped
public class MarketingConsentRepository implements PanacheRepositoryBase<MarketingConsent, UUID> {

    /**
     * Find all consent records for a specific user (customer).
     *
     * @param userId
     *            user UUID
     * @return list of consent records ordered by timestamp (newest first)
     */
    public List<MarketingConsent> findByCustomer(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId AND user.id = :userId ORDER BY consentedAt DESC",
                Parameters.with("tenantId", tenantId).and("userId", userId));
    }

    /**
     * Find active (consented) records for a specific channel.
     *
     * @param channel
     *            channel name (email, sms, push, phone)
     * @return list of customers with active consent
     */
    public List<MarketingConsent> findActiveByChannel(String channel) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId AND channel = :channel AND consented = true ORDER BY consentedAt DESC",
                Parameters.with("tenantId", tenantId).and("channel", channel));
    }

    /**
     * Get latest consent status for a customer and channel.
     *
     * @param userId
     *            user UUID
     * @param channel
     *            channel name
     * @return latest consent record or null if none exists
     */
    public MarketingConsent findLatestByCustomerAndChannel(UUID userId, String channel) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find("tenant.id = :tenantId AND user.id = :userId AND channel = :channel ORDER BY consentedAt DESC",
                Parameters.with("tenantId", tenantId).and("userId", userId).and("channel", channel)).firstResult();
    }

    /**
     * Find all consent records for current tenant (for exports).
     *
     * @return list of all consent records
     */
    public List<MarketingConsent> findByCurrentTenant() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list("tenant.id = :tenantId ORDER BY user.id, channel, consentedAt",
                Parameters.with("tenantId", tenantId));
    }
}
