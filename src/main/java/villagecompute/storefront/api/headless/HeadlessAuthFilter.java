package villagecompute.storefront.api.headless;

import java.io.IOException;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.OAuthClient;
import villagecompute.storefront.services.OAuthService;
import villagecompute.storefront.services.RateLimitService;
import villagecompute.storefront.services.RateLimitService.RateLimitResult;
import villagecompute.storefront.tenant.TenantContext;

/**
 * JAX-RS filter that authenticates and authorizes headless API requests.
 *
 * <p>
 * Executes after {@link villagecompute.storefront.tenant.TenantResolutionFilter} to validate OAuth client credentials
 * and enforce rate limits.
 *
 * <p>
 * <strong>Authentication Flow:</strong>
 * <ol>
 * <li>Extract Authorization header (Basic auth with client_id:client_secret)</li>
 * <li>Authenticate client credentials via {@link OAuthService}</li>
 * <li>Verify client belongs to current tenant (from TenantContext)</li>
 * <li>Check rate limit via {@link RateLimitService}</li>
 * <li>Store OAuth client in request property for downstream access</li>
 * </ol>
 *
 * <p>
 * <strong>Rate Limiting:</strong> Returns 429 Too Many Requests with RFC 7807 Problem Details and rate limit headers
 * (X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset).
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: OAuth client credential guard and rate limiting</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes enforcement)</li>
 * <li>Architecture: Section 6 Safety Net (token bucket rate limiting)</li>
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
@HeadlessApiBinding
public class HeadlessAuthFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOG = Logger.getLogger(HeadlessAuthFilter.class);
    private static final String ATTR_OAUTH_CLIENT = "headless.oauthClient";
    private static final String ATTR_RATE_LIMIT = "headless.rateLimit";
    private static final String ATTR_SCOPE = "headless.scope";

    @Inject
    OAuthService oauthService;

    @Inject
    RateLimitService rateLimitService;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Extract Authorization header
        String authHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            LOG.warn("Missing or invalid Authorization header for headless API");
            requestContext.abortWith(createUnauthorizedResponse("Missing or invalid Authorization header"));
            return;
        }

        // Decode Basic auth credentials
        String base64Credentials = authHeader.substring("Basic ".length());
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(base64Credentials));
        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid Base64 encoding in Authorization header");
            requestContext.abortWith(createUnauthorizedResponse("Invalid Authorization header encoding"));
            return;
        }

        String[] parts = credentials.split(":", 2);
        if (parts.length != 2) {
            LOG.warn("Invalid credentials format (expected client_id:client_secret)");
            requestContext.abortWith(createUnauthorizedResponse("Invalid credentials format"));
            return;
        }

        String clientId = parts[0];
        String clientSecret = parts[1];

        // Authenticate client
        Optional<OAuthClient> clientOpt = oauthService.authenticate(clientId, clientSecret);

        if (clientOpt.isEmpty()) {
            LOG.warnf("OAuth authentication failed - clientId=%s", clientId);
            requestContext.abortWith(createUnauthorizedResponse("Invalid client credentials"));
            return;
        }

        OAuthClient client = clientOpt.get();

        // Verify client belongs to current tenant
        if (!client.tenant.id.equals(TenantContext.getCurrentTenantId())) {
            LOG.warnf("Tenant mismatch - clientId=%s, clientTenantId=%s, requestTenantId=%s", clientId,
                    client.tenant.id, TenantContext.getCurrentTenantId());
            requestContext.abortWith(createForbiddenResponse("Client does not belong to this tenant"));
            return;
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        if (tenantId == null) {
            LOG.error("Tenant context missing before headless auth filter");
            requestContext.abortWith(createForbiddenResponse("Tenant context unavailable"));
            return;
        }

        // Extract requested scope from path (e.g., /api/v1/headless/catalog -> "catalog:read")
        String scope = determineScope(requestContext.getUriInfo().getPath(), requestContext.getMethod());

        if (scope != null && !client.hasScope(scope)) {
            LOG.warnf("Missing scope - clientId=%s, required=%s, scopes=%s", clientId, scope, client.scopes);
            requestContext.abortWith(createForbiddenResponse("Client missing required scope: " + scope));
            return;
        }

        int limitPerMinute = client.rateLimitPerMinute > 0 ? client.rateLimitPerMinute : 5000;

        // Check rate limit
        RateLimitResult rateLimitResult = rateLimitService.checkRateLimit(clientId, scope, limitPerMinute);
        requestContext.setProperty(ATTR_RATE_LIMIT, rateLimitResult);

        if (!rateLimitResult.allowed()) {
            LOG.warnf("Rate limit exceeded - clientId=%s, scope=%s", clientId, scope);
            requestContext.abortWith(createRateLimitResponse(rateLimitResult));
            return;
        }

        // Store OAuth client in request property for downstream access
        requestContext.setProperty(ATTR_OAUTH_CLIENT, client);
        requestContext.setProperty(ATTR_SCOPE, scope);

        LOG.debugf("Headless API request authenticated - clientId=%s, tenantId=%s, scope=%s", clientId,
                client.tenant.id, scope);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        RateLimitResult rateLimitResult = (RateLimitResult) requestContext.getProperty(ATTR_RATE_LIMIT);
        if (rateLimitResult == null) {
            return;
        }

        responseContext.getHeaders().putSingle("X-RateLimit-Limit", String.valueOf(rateLimitResult.limit()));
        responseContext.getHeaders().putSingle("X-RateLimit-Remaining",
                String.valueOf(Math.max(rateLimitResult.remaining(), 0)));
        responseContext.getHeaders().putSingle("X-RateLimit-Reset",
                String.valueOf(rateLimitResult.resetAt().getEpochSecond()));
    }

    private Response createUnauthorizedResponse(String detail) {
        return Response.status(Response.Status.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"Headless API\"").entity(java.util.Map.of("type",
                        "about:blank", "title", "Unauthorized", "status", 401, "detail", detail))
                .build();
    }

    private Response createForbiddenResponse(String detail) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(java.util.Map.of("type", "about:blank", "title", "Forbidden", "status", 403, "detail", detail))
                .build();
    }

    private Response createRateLimitResponse(RateLimitResult result) {
        return Response.status(429).header("X-RateLimit-Limit", result.limit())
                .header("X-RateLimit-Remaining", result.remaining())
                .header("X-RateLimit-Reset", result.resetAt().getEpochSecond())
                .header("Retry-After", calculateRetryAfter(result))
                .entity(java.util.Map.of("type", "about:blank", "title", "Too Many Requests", "status", 429, "detail",
                        "Rate limit exceeded. Please retry after " + calculateRetryAfter(result) + " seconds."))
                .build();
    }

    private long calculateRetryAfter(RateLimitResult result) {
        return Math.max(1, result.resetAt().getEpochSecond() - java.time.Instant.now().getEpochSecond());
    }

    private String determineScope(String rawPath, String method) {
        if (rawPath == null) {
            return "catalog:read";
        }
        String path = rawPath.startsWith("/") ? rawPath : "/" + rawPath;
        path = path.toLowerCase();
        String httpMethod = method == null ? "GET" : method.toUpperCase();

        if (path.startsWith("/api/v1/headless/catalog")) {
            return "catalog:read";
        }
        if (path.startsWith("/api/v1/headless/cart")) {
            return "GET".equals(httpMethod) ? "cart:read" : "cart:write";
        }
        if (path.startsWith("/api/v1/headless/orders")) {
            return "GET".equals(httpMethod) ? "orders:read" : "orders:write";
        }
        return "catalog:read";
    }
}
