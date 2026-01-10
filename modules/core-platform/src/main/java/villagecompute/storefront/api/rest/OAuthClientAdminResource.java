package villagecompute.storefront.api.rest;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.CreateOAuthClientRequest;
import villagecompute.storefront.api.types.OAuthClientCreateResponseDto;
import villagecompute.storefront.api.types.OAuthClientDto;
import villagecompute.storefront.api.types.RegenerateSecretResponseDto;
import villagecompute.storefront.data.models.OAuthClient;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.data.repositories.OAuthClientRepository;
import villagecompute.storefront.services.OAuthService;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.annotation.Timed;

/**
 * REST resource for OAuth client credential management.
 *
 * <p>
 * Provides admin endpoints for creating, listing, revoking, and regenerating OAuth client credentials for headless API
 * access.
 *
 * <p>
 * <strong>Endpoints:</strong>
 * <ul>
 * <li>GET /api/v1/admin/oauth-clients - List all OAuth clients for current tenant</li>
 * <li>POST /api/v1/admin/oauth-clients - Create new OAuth client</li>
 * <li>GET /api/v1/admin/oauth-clients/{clientId} - Get client details</li>
 * <li>PUT /api/v1/admin/oauth-clients/{clientId} - Update client (scopes, rate limit)</li>
 * <li>POST /api/v1/admin/oauth-clients/{clientId}/revoke - Revoke client (set active=false)</li>
 * <li>POST /api/v1/admin/oauth-clients/{clientId}/regenerate-secret - Regenerate client secret</li>
 * </ul>
 *
 * <p>
 * <strong>Security:</strong>
 * <ul>
 * <li>All endpoints require admin role (@RolesAllowed)</li>
 * <li>All operations verify tenant ownership to prevent cross-tenant access</li>
 * <li>Client secrets are ONLY exposed during creation/regeneration (never stored plaintext)</li>
 * <li>Secrets are hashed using BCrypt (12 rounds) via OAuthService</li>
 * </ul>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: OAuth client management UI + API</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * <li>OpenAPI: /admin/oauth-clients/** paths</li>
 * </ul>
 */
@Path("/api/v1/admin/oauth-clients")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OAuthClientAdminResource {

    private static final Logger LOG = Logger.getLogger(OAuthClientAdminResource.class);

    private static final int CLIENT_SECRET_LENGTH = 32;

    @Inject
    OAuthClientRepository oauthClientRepository;

    @Inject
    OAuthService oauthService;

    /**
     * List all OAuth clients for current tenant.
     *
     * @return list of OAuth clients (without secret hash)
     */
    @GET
    @RolesAllowed({"admin"})
    @Timed(
            value = "oauth.admin.list",
            description = "List OAuth clients")
    public Response listClients() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing OAuth clients for tenant: %s", tenantId);

        List<OAuthClient> clients = oauthClientRepository.findByTenant(tenantId);

        List<OAuthClientDto> dtos = clients.stream().map(this::toDto).collect(Collectors.toList());

        LOG.debugf("Found %d OAuth clients for tenant %s", dtos.size(), tenantId);
        return Response.ok(dtos).build();
    }

    /**
     * Create new OAuth client.
     *
     * @param request
     *            client creation request
     * @return created client with plaintext secret (ONLY time secret is shown)
     */
    @POST
    @RolesAllowed({"admin"})
    @Transactional
    @Timed(
            value = "oauth.admin.create",
            description = "Create OAuth client")
    public Response createClient(@Valid CreateOAuthClientRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Creating OAuth client '%s' for tenant %s", request.name, tenantId);

        // Generate secure client ID and secret
        String clientId = "oauth_" + UUID.randomUUID().toString().replace("-", "");
        String clientSecret = generateSecureSecret(CLIENT_SECRET_LENGTH);

        OAuthClient client = new OAuthClient();
        client.tenant = Tenant.findById(tenantId);
        client.clientId = clientId;
        client.clientSecretHash = oauthService.hashSecret(clientSecret);
        client.name = request.name;
        client.description = request.description;
        client.scopes = new HashSet<>(request.scopes);
        client.rateLimitPerMinute = request.rateLimitPerMinute != null ? request.rateLimitPerMinute : 5000;
        client.active = true;

        oauthClientRepository.persist(client);

        LOG.infof("Created OAuth client - clientId=%s, name=%s, scopes=%s, rateLimitPerMinute=%d", clientId,
                client.name, client.scopes, client.rateLimitPerMinute);

        // Return DTO with PLAIN secret (only time it's shown)
        OAuthClientCreateResponseDto dto = new OAuthClientCreateResponseDto();
        dto.id = client.id;
        dto.clientId = clientId;
        dto.clientSecret = clientSecret; // ONLY returned here
        dto.name = client.name;
        dto.scopes = client.scopes;
        dto.rateLimitPerMinute = client.rateLimitPerMinute;

        return Response.status(Response.Status.CREATED).entity(dto).build();
    }

    /**
     * Get OAuth client details.
     *
     * @param clientId
     *            OAuth client ID
     * @return client details (without secret hash)
     */
    @GET
    @Path("/{clientId}")
    @RolesAllowed({"admin"})
    @Timed(
            value = "oauth.admin.get",
            description = "Get OAuth client details")
    public Response getClient(@PathParam("clientId") String clientId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Getting OAuth client %s for tenant %s", clientId, tenantId);

        OAuthClient client = oauthClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new NotFoundException("OAuth client not found"));

        // Verify tenant ownership
        if (!client.tenant.id.equals(tenantId)) {
            LOG.warnf(
                    "Attempt to access OAuth client from another tenant - clientId=%s, requestorTenant=%s, clientTenant=%s",
                    clientId, tenantId, client.tenant.id);
            throw new NotFoundException("OAuth client not found");
        }

        return Response.ok(toDto(client)).build();
    }

    /**
     * Update OAuth client (scopes, rate limit).
     *
     * @param clientId
     *            OAuth client ID
     * @param request
     *            update request
     * @return updated client details
     */
    @PUT
    @Path("/{clientId}")
    @RolesAllowed({"admin"})
    @Transactional
    @Timed(
            value = "oauth.admin.update",
            description = "Update OAuth client")
    public Response updateClient(@PathParam("clientId") String clientId, @Valid CreateOAuthClientRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Updating OAuth client %s for tenant %s", clientId, tenantId);

        OAuthClient client = oauthClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new NotFoundException("OAuth client not found"));

        // Verify tenant ownership
        if (!client.tenant.id.equals(tenantId)) {
            LOG.warnf(
                    "Attempt to update OAuth client from another tenant - clientId=%s, requestorTenant=%s, clientTenant=%s",
                    clientId, tenantId, client.tenant.id);
            throw new NotFoundException("OAuth client not found");
        }

        // Update fields
        client.name = request.name;
        client.description = request.description;
        client.scopes = new HashSet<>(request.scopes);
        if (request.rateLimitPerMinute != null) {
            client.rateLimitPerMinute = request.rateLimitPerMinute;
        }

        oauthClientRepository.persist(client);

        LOG.infof("Updated OAuth client - clientId=%s, name=%s, scopes=%s, rateLimitPerMinute=%d", clientId,
                client.name, client.scopes, client.rateLimitPerMinute);

        return Response.ok(toDto(client)).build();
    }

    /**
     * Revoke OAuth client (set active=false).
     *
     * @param clientId
     *            OAuth client ID
     * @return no content (204)
     */
    @POST
    @Path("/{clientId}/revoke")
    @RolesAllowed({"admin"})
    @Transactional
    @Timed(
            value = "oauth.admin.revoke",
            description = "Revoke OAuth client")
    public Response revokeClient(@PathParam("clientId") String clientId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Revoking OAuth client %s for tenant %s", clientId, tenantId);

        OAuthClient client = oauthClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new NotFoundException("OAuth client not found"));

        // Verify tenant ownership
        if (!client.tenant.id.equals(tenantId)) {
            LOG.warnf(
                    "Attempt to revoke OAuth client from another tenant - clientId=%s, requestorTenant=%s, clientTenant=%s",
                    clientId, tenantId, client.tenant.id);
            throw new NotFoundException("OAuth client not found");
        }

        client.active = false;
        oauthClientRepository.persist(client);

        LOG.infof("Revoked OAuth client - clientId=%s", clientId);
        return Response.noContent().build();
    }

    /**
     * Regenerate OAuth client secret.
     *
     * @param clientId
     *            OAuth client ID
     * @return new client secret (ONLY time new secret is shown)
     */
    @POST
    @Path("/{clientId}/regenerate-secret")
    @RolesAllowed({"admin"})
    @Transactional
    @Timed(
            value = "oauth.admin.regenerate",
            description = "Regenerate OAuth client secret")
    public Response regenerateSecret(@PathParam("clientId") String clientId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("Regenerating secret for OAuth client %s for tenant %s", clientId, tenantId);

        OAuthClient client = oauthClientRepository.findByClientId(clientId)
                .orElseThrow(() -> new NotFoundException("OAuth client not found"));

        // Verify tenant ownership
        if (!client.tenant.id.equals(tenantId)) {
            LOG.warnf(
                    "Attempt to regenerate secret for OAuth client from another tenant - clientId=%s, requestorTenant=%s, clientTenant=%s",
                    clientId, tenantId, client.tenant.id);
            throw new NotFoundException("OAuth client not found");
        }

        String newSecret = generateSecureSecret(CLIENT_SECRET_LENGTH);
        client.clientSecretHash = oauthService.hashSecret(newSecret);
        oauthClientRepository.persist(client);

        LOG.infof("Regenerated secret for OAuth client - clientId=%s", clientId);

        RegenerateSecretResponseDto dto = new RegenerateSecretResponseDto();
        dto.clientId = clientId;
        dto.clientSecret = newSecret; // ONLY returned here

        return Response.ok(dto).build();
    }

    /**
     * Generate cryptographically secure random secret.
     *
     * @param length
     *            length in bytes (will be Base64 encoded)
     * @return URL-safe Base64 encoded secret
     */
    private String generateSecureSecret(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Convert OAuthClient entity to DTO (without secret hash).
     *
     * @param client
     *            OAuth client entity
     * @return DTO for API response
     */
    private OAuthClientDto toDto(OAuthClient client) {
        OAuthClientDto dto = new OAuthClientDto();
        dto.id = client.id;
        dto.clientId = client.clientId;
        dto.name = client.name;
        dto.description = client.description;
        dto.scopes = new HashSet<>(client.scopes);
        dto.active = client.active;
        dto.rateLimitPerMinute = client.rateLimitPerMinute;
        dto.createdAt = client.createdAt;
        dto.updatedAt = client.updatedAt;
        dto.lastUsedAt = client.lastUsedAt;
        // DO NOT include clientSecretHash
        return dto;
    }
}
