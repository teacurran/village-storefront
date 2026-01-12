package villagecompute.storefront.storecredit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.GiftCard;
import villagecompute.storefront.data.models.StoreCreditAccount;
import villagecompute.storefront.data.models.StoreCreditTransaction;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.models.User;
import villagecompute.storefront.services.CheckoutTenderService;
import villagecompute.storefront.services.ReportingProjectionService;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.panache.common.Parameters;

/**
 * Store credit domain service responsible for debits/credits, ledger entries, and POS friendly operations.
 */
@ApplicationScoped
public class StoreCreditService {

    private static final Logger LOG = Logger.getLogger(StoreCreditService.class);
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    @Inject
    EntityManager entityManager;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ReportingProjectionService reportingProjectionService;

    @Inject
    CheckoutTenderService checkoutTenderService;

    /**
     * Get or create store credit account for user.
     */
    @Transactional
    public StoreCreditAccount getOrCreateAccount(UUID userId) {
        Objects.requireNonNull(userId, "User id is required");
        return StoreCreditAccount.findOrCreateByUser(userId);
    }

    /**
     * Get store credit account for user if exists.
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<StoreCreditAccount> findAccount(UUID userId) {
        Objects.requireNonNull(userId, "User id is required");
        return Optional.ofNullable(StoreCreditAccount.findByUser(userId));
    }

    /**
     * List accounts for tenant with pagination.
     */
    @Transactional(TxType.SUPPORTS)
    public List<StoreCreditAccount> listAccounts(String status, String search, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);

        AccountQuery query = buildAccountQuery(status, search);
        String jpql = "select sca from StoreCreditAccount sca left join fetch sca.user where " + query.fetchWhereClause
                + " order by sca.createdAt desc";
        return StoreCreditAccount.find(jpql, query.parameters).page(safePage, safeSize).list();
    }

    @Transactional(TxType.SUPPORTS)
    public long countAccounts(String status, String search) {
        AccountQuery query = buildAccountQuery(status, search);
        return StoreCreditAccount.count(query.whereClause, query.parameters);
    }

    /**
     * List ledger entries for account.
     */
    @Transactional(TxType.SUPPORTS)
    public List<StoreCreditTransaction> listTransactions(Long accountId, int page, int size) {
        return StoreCreditTransaction.findByAccount(accountId, Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    /**
     * Redeem store credit amount with idempotency safeguards.
     */
    @Transactional
    public StoreCreditTransaction redeem(UUID userId, BigDecimal requestedAmount, UUID orderId, String idempotencyKey,
            Long posDeviceId, OffsetDateTime offlineSyncedAt) {
        Objects.requireNonNull(userId, "User id is required");
        validateAmount(requestedAmount);
        requireIdempotencyKey(idempotencyKey);

        StoreCreditTransaction existing = StoreCreditTransaction.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            LOG.infof("Returning existing store credit redemption (idempotent) - transactionId=%s", existing.id);
            return existing;
        }

        StoreCreditAccount account = lockAccountByUser(userId);
        if (!"active".equalsIgnoreCase(account.status)) {
            throw new IllegalStateException("Store credit account is not active");
        }

        BigDecimal normalized = normalizeAmount(requestedAmount);
        BigDecimal amountToApply = account.balance.min(normalized);
        if (amountToApply.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Insufficient store credit balance");
        }

        account.balance = account.balance.subtract(amountToApply);

        StoreCreditTransaction transaction = new StoreCreditTransaction();
        transaction.tenant = account.tenant;
        transaction.account = account;
        transaction.amount = amountToApply.negate();
        transaction.transactionType = "redeemed";
        transaction.balanceAfter = account.balance;
        transaction.orderId = orderId;
        transaction.idempotencyKey = idempotencyKey;
        transaction.posDeviceId = posDeviceId;
        transaction.offlineSyncedAt = offlineSyncedAt;
        transaction.reason = amountToApply.compareTo(normalized) < 0 ? "Partial redemption applied"
                : "Checkout redemption";
        transaction.persist();

        checkoutTenderService.recordStoreCreditTender(orderId, transaction);
        reportingProjectionService.recordStoreCreditLedgerEvent(transaction);
        meterRegistry.counter("storecredit.redeemed", "tenant_id", TenantContext.getCurrentTenantId().toString())
                .increment();

        return transaction;
    }

    /**
     * Admin adjustments (positive credit or negative debit) for a user's account.
     */
    @Transactional
    public StoreCreditTransaction adjust(UUID userId, BigDecimal amount, String reason) {
        Objects.requireNonNull(userId, "User id is required");
        validateAmount(amount.abs());

        StoreCreditAccount account = lockAccountByUser(userId);

        BigDecimal normalized = normalizeAmount(amount);
        BigDecimal newBalance = account.balance.add(normalized);
        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalStateException("Adjustment would result in negative balance");
        }
        account.balance = newBalance;

        StoreCreditTransaction transaction = new StoreCreditTransaction();
        transaction.tenant = account.tenant;
        transaction.account = account;
        transaction.amount = normalized;
        transaction.transactionType = normalized.signum() >= 0 ? "adjusted" : "redeemed";
        transaction.balanceAfter = account.balance;
        transaction.reason = reason;
        transaction.persist();

        reportingProjectionService.recordStoreCreditLedgerEvent(transaction);
        meterRegistry.counter("storecredit.adjusted", "tenant_id", TenantContext.getCurrentTenantId().toString(),
                "direction", normalized.signum() >= 0 ? "credit" : "debit").increment();

        return transaction;
    }

    /**
     * Credit store credit balance from gift card conversion.
     */
    @Transactional
    public StoreCreditTransaction creditFromGiftCard(GiftCard giftCard, UUID userId, BigDecimal amount, String reason) {
        Objects.requireNonNull(giftCard, "Gift card is required");
        Objects.requireNonNull(userId, "User id is required");
        validateAmount(amount);

        StoreCreditAccount account = lockAccountByUser(userId);
        account.balance = account.balance.add(normalizeAmount(amount));

        StoreCreditTransaction transaction = new StoreCreditTransaction();
        transaction.tenant = account.tenant;
        transaction.account = account;
        transaction.giftCard = giftCard;
        transaction.amount = amount;
        transaction.transactionType = "converted";
        transaction.balanceAfter = account.balance;
        transaction.reason = reason != null ? reason : "Converted from gift card";
        transaction.persist();

        reportingProjectionService.recordStoreCreditLedgerEvent(transaction);
        meterRegistry.counter("storecredit.converted", "tenant_id", TenantContext.getCurrentTenantId().toString())
                .increment();

        return transaction;
    }

    private StoreCreditAccount lockAccountByUser(UUID userId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        StoreCreditAccount account = entityManager.createQuery(
                "select sca from StoreCreditAccount sca where sca.user.id = :userId and sca.tenant.id = :tenant",
                StoreCreditAccount.class).setParameter("userId", userId).setParameter("tenant", tenantId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst().orElse(null);

        if (account == null) {
            account = new StoreCreditAccount();
            account.tenant = Tenant.findById(tenantId);
            User user = User.findById(userId);
            if (user == null || !tenantId.equals(user.tenant.id)) {
                throw new IllegalArgumentException("User not found for current tenant");
            }
            account.user = user;
            account.balance = BigDecimal.ZERO;
            entityManager.persist(account);
            entityManager.flush();
        }
        return account;
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        return amount.setScale(2, MONEY_ROUNDING);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
    }

    private AccountQuery buildAccountQuery(String status, String search) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        StringBuilder whereClause = new StringBuilder("tenant.id = :tenant");
        StringBuilder fetchWhereClause = new StringBuilder("sca.tenant.id = :tenant");
        Parameters parameters = Parameters.with("tenant", tenantId);

        if (status != null && !status.isBlank()) {
            whereClause.append(" and lower(status) = :status");
            fetchWhereClause.append(" and lower(sca.status) = :status");
            parameters.and("status", status.toLowerCase(Locale.US));
        }

        if (search != null && !search.isBlank()) {
            String trimmed = search.trim();
            UUID searchUuid = tryParseUuid(trimmed);
            if (searchUuid != null) {
                whereClause.append(" and user.id = :searchUserId");
                fetchWhereClause.append(" and sca.user.id = :searchUserId");
                parameters.and("searchUserId", searchUuid);
            } else {
                whereClause.append(" and lower(user.email) like :searchLike");
                fetchWhereClause.append(" and lower(sca.user.email) like :searchLike");
                parameters.and("searchLike", "%" + trimmed.toLowerCase(Locale.US) + "%");
            }
        }

        return new AccountQuery(whereClause.toString(), fetchWhereClause.toString(), parameters);
    }

    private UUID tryParseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private record AccountQuery(String whereClause, String fetchWhereClause, Parameters parameters) {
    }
}
