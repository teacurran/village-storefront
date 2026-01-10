package villagecompute.storefront.services.metrics;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Centralized metrics helper for headless API and OAuth operations.
 *
 * <p>
 * Provides consistent metric naming and tagging for headless API operations including OAuth client management, token
 * issuance, rate limiting, and API endpoint usage. All metrics include tenant_id tags for multi-tenant observability.
 *
 * <p>
 * Metric Types:
 * <ul>
 * <li>Counters: Operation counts (token issuance, rate limit hits, API calls)</li>
 * <li>Timers: Operation latency histograms (token issuance, API request duration)</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T7: Observability instrumentation for I5 modules</li>
 * <li>Architecture §3.7: Observability Fabric</li>
 * <li>Architecture §3.20.2: Sev2 Alert for OAuth client credential issuance errors >5%</li>
 * <li>Foundation §3.0: Rulebook (structured logging + metrics)</li>
 * </ul>
 */
@ApplicationScoped
public class HeadlessApiMetrics {

    @Inject
    MeterRegistry meterRegistry;

    // ========================================
    // OAuth Client Management Metrics
    // ========================================

    /**
     * Record OAuth client created.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientType
     *            client type (e.g., "confidential", "public")
     */
    public void recordClientCreated(UUID tenantId, String clientType) {
        meterRegistry
                .counter("headless.oauth.client.created", "tenant_id", tenantId.toString(), "client_type", clientType)
                .increment();
    }

    /**
     * Record OAuth client revoked.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param reason
     *            revocation reason (e.g., "security_breach", "user_request", "expired")
     */
    public void recordClientRevoked(UUID tenantId, String clientId, String reason) {
        meterRegistry.counter("headless.oauth.client.revoked", "tenant_id", tenantId.toString(), "reason", reason)
                .increment();
    }

    /**
     * Record OAuth client credentials rotated.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     */
    public void recordClientCredentialsRotated(UUID tenantId, String clientId) {
        counter("headless.oauth.client.rotated", tenantId).increment();
    }

    // ========================================
    // Token Issuance Metrics
    // ========================================

    /**
     * Record OAuth token issued.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param grantType
     *            grant type (e.g., "client_credentials", "authorization_code", "refresh_token")
     */
    public void recordTokenIssued(UUID tenantId, String clientId, String grantType) {
        meterRegistry.counter("headless.oauth.token.issued", "tenant_id", tenantId.toString(), "client_id", clientId,
                "grant_type", grantType).increment();
    }

    /**
     * Record OAuth token issuance failure.
     *
     * <p>
     * Critical for monitoring Sev2 alert threshold of >5% errors per Architecture §3.20.2
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param grantType
     *            grant type
     * @param failureReason
     *            failure reason (e.g., "invalid_credentials", "invalid_grant", "client_not_found")
     */
    public void recordTokenIssuanceFailed(UUID tenantId, String clientId, String grantType, String failureReason) {
        meterRegistry.counter("headless.oauth.token.failed", "tenant_id", tenantId.toString(), "client_id", clientId,
                "grant_type", grantType, "reason", failureReason).increment();
    }

    /**
     * Record OAuth token issuance duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            token issuance duration in milliseconds
     */
    public void recordTokenIssuanceDuration(UUID tenantId, long durationMillis) {
        timer("headless.oauth.token.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record OAuth token refresh.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     */
    public void recordTokenRefreshed(UUID tenantId, String clientId) {
        meterRegistry.counter("headless.oauth.token.refreshed", "tenant_id", tenantId.toString(), "client_id", clientId)
                .increment();
    }

    /**
     * Record OAuth token revocation.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param reason
     *            revocation reason (e.g., "logout", "security_event", "manual_revocation")
     */
    public void recordTokenRevoked(UUID tenantId, String clientId, String reason) {
        meterRegistry.counter("headless.oauth.token.revoked", "tenant_id", tenantId.toString(), "client_id", clientId,
                "reason", reason).increment();
    }

    // ========================================
    // Rate Limiting Metrics
    // ========================================

    /**
     * Record rate limit hit (request throttled).
     *
     * <p>
     * Critical for understanding client behavior and tuning rate limits per Architecture §3.20.2
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     * @param limitType
     *            limit type (e.g., "per_second", "per_minute", "per_hour")
     */
    public void recordRateLimitHit(UUID tenantId, String clientId, String endpoint, String limitType) {
        meterRegistry.counter("headless.api.rate_limit.hit", "tenant_id", tenantId.toString(), "client_id", clientId,
                "endpoint", endpoint, "limit_type", limitType).increment();
    }

    /**
     * Record rate limit bypass (exempted client).
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     */
    public void recordRateLimitBypassed(UUID tenantId, String clientId, String endpoint) {
        meterRegistry.counter("headless.api.rate_limit.bypassed", "tenant_id", tenantId.toString(), "client_id",
                clientId, "endpoint", endpoint).increment();
    }

    /**
     * Record current rate limit quota usage.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     * @param usagePercent
     *            current usage as percentage of limit (0-100)
     */
    public void recordRateLimitUsage(UUID tenantId, String clientId, String endpoint, double usagePercent) {
        meterRegistry.gauge("headless.api.rate_limit.usage",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString()),
                        io.micrometer.core.instrument.Tag.of("client_id", clientId),
                        io.micrometer.core.instrument.Tag.of("endpoint", endpoint)),
                usagePercent);
    }

    // ========================================
    // API Request Metrics
    // ========================================

    /**
     * Record headless API request.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     * @param method
     *            HTTP method (GET, POST, PUT, DELETE, etc.)
     */
    public void recordApiRequest(UUID tenantId, String clientId, String endpoint, String method) {
        meterRegistry.counter("headless.api.request", "tenant_id", tenantId.toString(), "client_id", clientId,
                "endpoint", endpoint, "method", method).increment();
    }

    /**
     * Record API request duration.
     *
     * @param tenantId
     *            tenant identifier
     * @param endpoint
     *            API endpoint path
     * @param durationMillis
     *            request duration in milliseconds
     */
    public void recordApiRequestDuration(UUID tenantId, String endpoint, long durationMillis) {
        meterRegistry.timer("headless.api.request.duration", "tenant_id", tenantId.toString(), "endpoint", endpoint)
                .record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record API request success.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     * @param statusCode
     *            HTTP status code
     */
    public void recordApiRequestSuccess(UUID tenantId, String clientId, String endpoint, int statusCode) {
        meterRegistry.counter("headless.api.request.success", "tenant_id", tenantId.toString(), "client_id", clientId,
                "endpoint", endpoint, "status_code", String.valueOf(statusCode)).increment();
    }

    /**
     * Record API request error.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     * @param statusCode
     *            HTTP status code
     * @param errorType
     *            error type (e.g., "validation_error", "not_found", "server_error")
     */
    public void recordApiRequestError(UUID tenantId, String clientId, String endpoint, int statusCode,
            String errorType) {
        meterRegistry
                .counter("headless.api.request.error", "tenant_id", tenantId.toString(), "client_id", clientId,
                        "endpoint", endpoint, "status_code", String.valueOf(statusCode), "error_type", errorType)
                .increment();
    }

    // ========================================
    // Scope and Permission Metrics
    // ========================================

    /**
     * Record scope validation.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param requestedScope
     *            requested scope
     * @param granted
     *            whether scope was granted
     */
    public void recordScopeValidation(UUID tenantId, String clientId, String requestedScope, boolean granted) {
        String status = granted ? "granted" : "denied";
        meterRegistry.counter("headless.oauth.scope.validation", "tenant_id", tenantId.toString(), "client_id",
                clientId, "scope", requestedScope, "status", status).increment();
    }

    /**
     * Record permission denied event.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param endpoint
     *            API endpoint path
     * @param requiredPermission
     *            required permission that was missing
     */
    public void recordPermissionDenied(UUID tenantId, String clientId, String endpoint, String requiredPermission) {
        meterRegistry.counter("headless.api.permission.denied", "tenant_id", tenantId.toString(), "client_id", clientId,
                "endpoint", endpoint, "required_permission", requiredPermission).increment();
    }

    // ========================================
    // KPI Metrics (Component Performance Indicators)
    // ========================================

    /**
     * Record active OAuth clients count.
     *
     * @param tenantId
     *            tenant identifier
     * @param activeClients
     *            number of active OAuth clients
     */
    public void recordActiveClientsCount(UUID tenantId, int activeClients) {
        meterRegistry.gauge("headless.oauth.clients.active",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("tenant_id", tenantId.toString())),
                activeClients);
    }

    /**
     * Record API usage by partner integration.
     *
     * @param tenantId
     *            tenant identifier
     * @param partnerName
     *            partner/integration name
     * @param requestCount
     *            total requests from this partner
     */
    public void recordPartnerApiUsage(UUID tenantId, String partnerName, long requestCount) {
        meterRegistry.counter("headless.api.partner.usage", "tenant_id", tenantId.toString(), "partner", partnerName)
                .increment(requestCount);
    }

    /**
     * Record OAuth client onboarding completion.
     *
     * @param tenantId
     *            tenant identifier
     * @param durationMillis
     *            time from client creation to first successful token issuance in milliseconds
     */
    public void recordClientOnboardingCompleted(UUID tenantId, long durationMillis) {
        counter("headless.oauth.client.onboarding.completed", tenantId).increment();
        timer("headless.oauth.client.onboarding.duration", tenantId).record(durationMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Record OAuth client credential issuance error (for monitoring Sev2 alert).
     *
     * <p>
     * Target threshold: <5% error rate during partner onboarding week per Architecture §3.20.2
     *
     * @param tenantId
     *            tenant identifier
     * @param errorType
     *            error type (e.g., "generation_failed", "storage_error", "encryption_failed")
     */
    public void recordClientCredentialIssuanceError(UUID tenantId, String errorType) {
        meterRegistry.counter("headless.oauth.client.credential.issuance.error", "tenant_id", tenantId.toString(),
                "error_type", errorType).increment();
    }

    /**
     * Record webhook delivery for headless API event.
     *
     * @param tenantId
     *            tenant identifier
     * @param clientId
     *            OAuth client identifier
     * @param eventType
     *            event type delivered via webhook
     * @param success
     *            whether delivery succeeded
     */
    public void recordWebhookDelivery(UUID tenantId, String clientId, String eventType, boolean success) {
        String status = success ? "success" : "failed";
        meterRegistry.counter("headless.api.webhook.delivery", "tenant_id", tenantId.toString(), "client_id", clientId,
                "event_type", eventType, "status", status).increment();
    }

    // ========================================
    // Helper Methods
    // ========================================

    /**
     * Get or create a counter with tenant_id tag.
     *
     * @param name
     *            metric name
     * @param tenantId
     *            tenant identifier
     * @return counter instance
     */
    private Counter counter(String name, UUID tenantId) {
        return meterRegistry.counter(name, "tenant_id", tenantId.toString());
    }

    /**
     * Get or create a timer with tenant_id tag.
     *
     * @param name
     *            metric name
     * @param tenantId
     *            tenant identifier
     * @return timer instance
     */
    private Timer timer(String name, UUID tenantId) {
        return meterRegistry.timer(name, "tenant_id", tenantId.toString());
    }
}
