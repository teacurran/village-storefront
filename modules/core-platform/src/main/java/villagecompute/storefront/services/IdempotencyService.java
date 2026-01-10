package villagecompute.storefront.services;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.IdempotencyKey;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.exceptions.IdempotencyConflictException;
import villagecompute.storefront.tenant.TenantContext;

/**
 * Service for managing idempotency keys to prevent duplicate request processing.
 *
 * <p>
 * Implements idempotency semantics per ADR-003 checkout saga requirements. Provides atomic key acquisition, result
 * caching, and state management (PENDING/SUCCESS/FAILED).
 *
 * <p>
 * References:
 * <ul>
 * <li>ADR-003: Checkout saga decision (Section 3: Idempotency keys)</li>
 * <li>Task I3.T1: Idempotency support for cart/checkout operations</li>
 * <li>Architecture: Preventing duplicate submissions</li>
 * </ul>
 */
@ApplicationScoped
public class IdempotencyService {

    private static final Logger LOG = Logger.getLogger(IdempotencyService.class);

    /**
     * Acquire an idempotency key atomically. If key already exists, returns existing record.
     *
     * @param key
     *            client-provided idempotency key
     * @param operationType
     *            operation identifier (e.g., "checkout", "payment")
     * @param ttlMinutes
     *            time-to-live in minutes
     * @return idempotency key record
     * @throws IdempotencyConflictException
     *             if key exists and is still PENDING
     */
    @Transactional
    public IdempotencyKey acquire(String key, String operationType, int ttlMinutes) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Acquiring idempotency key - tenantId=%s, key=%s, operationType=%s", tenantId, key, operationType);

        // Check for existing key
        Optional<IdempotencyKey> existing = IdempotencyKey
                .<IdempotencyKey>find("idempotencyKey = ?1 AND tenant.id = ?2", key, tenantId).firstResultOptional();

        if (existing.isPresent()) {
            IdempotencyKey existingKey = existing.get();

            if (existingKey.isExpired()) {
                LOG.infof("Idempotency key expired - tenantId=%s, key=%s, status=%s", tenantId, key,
                        existingKey.status);
                // Allow reuse of expired keys by deleting and creating new
                existingKey.delete();
                return createNewKey(key, operationType, ttlMinutes, tenantId);
            }

            if (existingKey.isPending()) {
                LOG.warnf("Idempotency key conflict - tenantId=%s, key=%s (request still processing)", tenantId, key);
                throw new IdempotencyConflictException(
                        "Request with idempotency key " + key + " is still being processed");
            }

            LOG.infof("Idempotency key found - tenantId=%s, key=%s, status=%s", tenantId, key, existingKey.status);
            return existingKey;
        }

        return createNewKey(key, operationType, ttlMinutes, tenantId);
    }

    /**
     * Create a new idempotency key record.
     */
    private IdempotencyKey createNewKey(String key, String operationType, int ttlMinutes, UUID tenantId) {
        IdempotencyKey idempotencyKey = new IdempotencyKey();
        idempotencyKey.idempotencyKey = key;
        idempotencyKey.tenant = Tenant.findById(tenantId);
        idempotencyKey.operationType = operationType;
        idempotencyKey.status = IdempotencyKey.Status.PENDING;
        idempotencyKey.expiresAt = OffsetDateTime.now().plusMinutes(ttlMinutes);

        try {
            idempotencyKey.persist();
            LOG.infof("Created new idempotency key - tenantId=%s, key=%s, operationType=%s", tenantId, key,
                    operationType);
            return idempotencyKey;
        } catch (PersistenceException e) {
            // Handle race condition: another thread created the key between our check and insert
            LOG.warnf(e, "Race condition creating idempotency key - tenantId=%s, key=%s", tenantId, key);
            Optional<IdempotencyKey> raceResult = IdempotencyKey
                    .<IdempotencyKey>find("idempotencyKey = ?1 AND tenant.id = ?2", key, tenantId)
                    .firstResultOptional();
            if (raceResult.isPresent()) {
                IdempotencyKey raceKey = raceResult.get();
                if (raceKey.isPending()) {
                    throw new IdempotencyConflictException(
                            "Request with idempotency key " + key + " is still being processed");
                }
                return raceKey;
            }
            throw e; // Re-throw if we can't find the key
        }
    }

    /**
     * Mark idempotency key as successfully completed with result.
     *
     * @param key
     *            idempotency key
     * @param result
     *            JSON-encoded result
     * @param responseCode
     *            HTTP response code
     */
    @Transactional
    public void markSuccess(String key, String result, int responseCode) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Marking idempotency key success - tenantId=%s, key=%s, responseCode=%d", tenantId, key,
                responseCode);

        IdempotencyKey idempotencyKey = IdempotencyKey
                .<IdempotencyKey>find("idempotencyKey = ?1 AND tenant.id = ?2", key, tenantId).firstResultOptional()
                .orElseThrow(() -> new IllegalArgumentException("Idempotency key not found: " + key));

        idempotencyKey.status = IdempotencyKey.Status.SUCCESS;
        idempotencyKey.result = result;
        idempotencyKey.responseCode = responseCode;
        idempotencyKey.completedAt = OffsetDateTime.now();
        idempotencyKey.persist();

        LOG.infof("Idempotency key marked success - tenantId=%s, key=%s", tenantId, key);
    }

    /**
     * Mark idempotency key as failed with error details.
     *
     * @param key
     *            idempotency key
     * @param error
     *            JSON-encoded error
     * @param responseCode
     *            HTTP response code
     */
    @Transactional
    public void markFailed(String key, String error, int responseCode) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Marking idempotency key failed - tenantId=%s, key=%s, responseCode=%d", tenantId, key, responseCode);

        IdempotencyKey idempotencyKey = IdempotencyKey
                .<IdempotencyKey>find("idempotencyKey = ?1 AND tenant.id = ?2", key, tenantId).firstResultOptional()
                .orElseThrow(() -> new IllegalArgumentException("Idempotency key not found: " + key));

        idempotencyKey.status = IdempotencyKey.Status.FAILED;
        idempotencyKey.error = error;
        idempotencyKey.responseCode = responseCode;
        idempotencyKey.completedAt = OffsetDateTime.now();
        idempotencyKey.persist();

        LOG.infof("Idempotency key marked failed - tenantId=%s, key=%s", tenantId, key);
    }

    /**
     * Get cached result for an idempotency key.
     *
     * @param key
     *            idempotency key
     * @return idempotency key record if exists
     */
    public Optional<IdempotencyKey> getResult(String key) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        return IdempotencyKey.<IdempotencyKey>find("idempotencyKey = ?1 AND tenant.id = ?2", key, tenantId)
                .firstResultOptional();
    }

    /**
     * Delete expired idempotency keys (cleanup job).
     *
     * @return number of keys deleted
     */
    @Transactional
    public long cleanupExpired() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Cleaning up expired idempotency keys - tenantId=%s", tenantId);

        long deleted = IdempotencyKey.delete("tenant.id = ?1 AND expiresAt < ?2", tenantId, OffsetDateTime.now());

        LOG.infof("Cleaned up %d expired idempotency keys - tenantId=%s", deleted, tenantId);
        return deleted;
    }
}
