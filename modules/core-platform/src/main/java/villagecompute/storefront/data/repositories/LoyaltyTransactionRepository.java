package villagecompute.storefront.data.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.data.models.LoyaltyTransaction;
import villagecompute.storefront.tenant.TenantContext;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;

/**
 * Repository for LoyaltyTransaction entity with tenant-aware queries.
 *
 * <p>
 * All queries automatically scope to the current tenant from {@link TenantContext}. This prevents cross-tenant data
 * leakage and enforces the multi-tenancy model defined in ADR-001.
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-001: Repository-Level Tenant Enforcement</li>
 * <li>Entity: {@link LoyaltyTransaction}</li>
 * <li>Task I4.T4: Loyalty transaction ledger with idempotency support</li>
 * </ul>
 */
@ApplicationScoped
public class LoyaltyTransactionRepository implements PanacheRepositoryBase<LoyaltyTransaction, UUID> {

    private static final String QUERY_FIND_BY_MEMBER = "tenant.id = :tenantId and member.id = :memberId order by createdAt desc";
    private static final String QUERY_FIND_BY_ORDER = "tenant.id = :tenantId and orderId = :orderId";
    private static final String QUERY_FIND_BY_IDEMPOTENCY_KEY = "tenant.id = :tenantId and idempotencyKey = :idempotencyKey";
    private static final String QUERY_FIND_EXPIRING = "tenant.id = :tenantId and transactionType in ('earned','adjusted') and expiresAt <= :expiresAt and expiresAt is not null order by expiresAt asc";
    private static final String QUERY_FIND_EXPIRED_REASON = "tenant.id = :tenantId and transactionType = 'expired' and reason = :reason";
    private static final String QUERY_FIND_BY_TYPE = "tenant.id = :tenantId and transactionType = :transactionType order by createdAt desc";

    /**
     * Find transactions by member within current tenant.
     *
     * @param memberId
     *            member UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of transactions
     */
    public List<LoyaltyTransaction> findByMember(UUID memberId, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_MEMBER, Parameters.with("tenantId", tenantId).and("memberId", memberId))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find transactions by order ID within current tenant.
     *
     * @param orderId
     *            order UUID
     * @return list of transactions for order
     */
    public List<LoyaltyTransaction> findByOrder(UUID orderId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return list(QUERY_FIND_BY_ORDER, Parameters.with("tenantId", tenantId).and("orderId", orderId));
    }

    /**
     * Find transaction by idempotency key within current tenant.
     *
     * @param idempotencyKey
     *            idempotency key
     * @return transaction if found
     */
    public Optional<LoyaltyTransaction> findByIdempotencyKey(String idempotencyKey) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_IDEMPOTENCY_KEY,
                Parameters.with("tenantId", tenantId).and("idempotencyKey", idempotencyKey)).firstResultOptional();
    }

    /**
     * Find expiring transactions within current tenant.
     *
     * @param expiresAt
     *            cutoff timestamp
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of expiring transactions
     */
    public List<LoyaltyTransaction> findExpiring(OffsetDateTime expiresAt, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_EXPIRING, Parameters.with("tenantId", tenantId).and("expiresAt", expiresAt))
                .page(Page.of(page, size)).list();
    }

    /**
     * Find transactions by type within current tenant.
     *
     * @param transactionType
     *            transaction type
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return paginated list of transactions
     */
    public List<LoyaltyTransaction> findByType(String transactionType, int page, int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return find(QUERY_FIND_BY_TYPE, Parameters.with("tenantId", tenantId).and("transactionType", transactionType))
                .page(Page.of(page, size)).list();
    }

    /**
     * Count transactions by member within current tenant.
     *
     * @param memberId
     *            member UUID
     * @return transaction count
     */
    public long countByMember(UUID memberId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return count("tenant.id = :tenantId and member.id = :memberId",
                Parameters.with("tenantId", tenantId).and("memberId", memberId));
    }

    /**
     * Check whether an expiration transaction already exists for the provided original transaction.
     *
     * @param originalTransactionId
     *            source transaction identifier
     * @return true if an expiration entry already exists
     */
    public boolean hasExpirationEntry(UUID originalTransactionId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String reason = "Points expired from transaction " + originalTransactionId;
        long count = count(QUERY_FIND_EXPIRED_REASON, Parameters.with("tenantId", tenantId).and("reason", reason));
        return count > 0;
    }
}
