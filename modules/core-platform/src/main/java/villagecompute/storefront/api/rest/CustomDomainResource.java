package villagecompute.storefront.api.rest;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern.Flag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import villagecompute.storefront.data.models.CustomDomain;
import villagecompute.storefront.data.models.Tenant;
import villagecompute.storefront.tenant.CustomDomainVerified;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.annotation.Timed;

/**
 * REST resource for custom domain management.
 *
 * <p>
 * Provides admin endpoints for adding, listing, and removing custom domains for tenant stores. Domains are validated
 * asynchronously by DomainValidationJob after creation.
 *
 * <p>
 * <strong>Endpoints:</strong>
 * <ul>
 * <li>GET /api/v1/admin/custom-domains - List all custom domains for current tenant</li>
 * <li>POST /api/v1/admin/custom-domains - Add new custom domain (triggers verification job)</li>
 * <li>GET /api/v1/admin/custom-domains/{domainId} - Get domain details including verification status</li>
 * <li>DELETE /api/v1/admin/custom-domains/{domainId} - Remove custom domain</li>
 * </ul>
 *
 * <p>
 * <strong>Security:</strong>
 * <ul>
 * <li>All endpoints require admin role (@RolesAllowed)</li>
 * <li>All operations verify tenant ownership to prevent cross-tenant access</li>
 * <li>Domain uniqueness enforced at database level (constraint violation returns 409 Conflict)</li>
 * </ul>
 *
 * <p>
 * <strong>Domain Verification Workflow:</strong>
 * <ol>
 * <li>Admin POSTs domain → server generates verification token → returns DNS instructions</li>
 * <li>Admin adds TXT record: _acme-challenge.example.com → "token123"</li>
 * <li>DomainValidationJob (scheduled) queries DNS, validates token, updates status → ACTIVE</li>
 * <li>CertificateEventHandler listens for CustomDomainVerified event → creates K8s Certificate CRD</li>
 * <li>cert-manager provisions Let's Encrypt certificate → updates domain.certificateExpiry</li>
 * </ol>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: Custom Domain SSL Automation</li>
 * <li>Migration: V20260132__custom_domain_ssl_automation.sql</li>
 * <li>Architecture: docs/architecture/tenant_isolation.md (Tenant Access Gateway)</li>
 * <li>Runbook: docs/operations/runbook.md (Custom Domain SSL Renewal)</li>
 * </ul>
 */
@Path("/api/v1/admin/custom-domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CustomDomainResource {

    private static final Logger LOG = Logger.getLogger(CustomDomainResource.class);

    // RFC 1035 compliant domain pattern
    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^[a-z0-9]([a-z0-9-\\.]{0,251}[a-z0-9])?$",
            Pattern.CASE_INSENSITIVE);

    @Inject
    ObjectMapper objectMapper;

    @Inject
    Event<CustomDomainVerified> domainVerifiedEvent;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * List all custom domains for the current tenant.
     *
     * @return List of custom domain DTOs including verification status
     */
    @GET
    @Timed(
            value = "custom_domains.list",
            description = "Time to list custom domains")
    @RolesAllowed({"ADMIN", "PLATFORM_ADMIN"})
    public Response listDomains() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.debugf("Listing custom domains for tenant %s", tenantId);

        List<CustomDomain> domains = CustomDomain.list("tenant.id = ?1 ORDER BY createdAt DESC", tenantId);

        var response = domains.stream().map(this::toDomainDto).collect(Collectors.toList());

        return Response.ok(response).build();
    }

    /**
     * Add a new custom domain for the current tenant.
     *
     * <p>
     * Generates a verification token and returns DNS TXT record instructions. Domain status is set to PENDING and will
     * be verified asynchronously by DomainValidationJob.
     *
     * @param request
     *            Domain creation request containing the domain name
     * @return Created domain DTO with DNS verification instructions
     */
    @POST
    @Transactional
    @Timed(
            value = "custom_domains.create",
            description = "Time to create custom domain")
    @RolesAllowed({"ADMIN", "PLATFORM_ADMIN"})
    public Response addDomain(@Valid AddDomainRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String domain = request.domain.toLowerCase().trim();

        LOG.infof("Adding custom domain %s for tenant %s", domain, tenantId);

        // Validate domain format
        if (!DOMAIN_PATTERN.matcher(domain).matches()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("Invalid domain format. Must be RFC 1035 compliant FQDN.")).build();
        }

        // Check for duplicates across all tenants (DB constraint will catch this too, but fail fast)
        long existingCount = CustomDomain.count("domain = ?1", domain);
        if (existingCount > 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("Domain already registered to another tenant.")).build();
        }

        // Generate verification token (32 bytes = 44 base64 chars)
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String verificationToken = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);

        // Create DNS challenge JSON payload
        ObjectNode dnsChallenge = objectMapper.createObjectNode();
        dnsChallenge.put("type", "TXT");
        dnsChallenge.put("name", "_acme-challenge." + domain);
        dnsChallenge.put("value", verificationToken);

        // Persist domain with PENDING status
        Tenant tenant = Tenant.findById(tenantId);
        CustomDomain customDomain = new CustomDomain();
        customDomain.tenant = tenant;
        customDomain.domain = domain;
        customDomain.verified = false;
        customDomain.verificationToken = verificationToken;
        customDomain.status = CustomDomain.DomainStatus.PENDING;
        customDomain.dnsChallenge = dnsChallenge;
        customDomain.verificationRetryCount = 0;
        customDomain.createdAt = OffsetDateTime.now();
        customDomain.updatedAt = OffsetDateTime.now();
        customDomain.persist();

        LOG.infof("Created custom domain %s (id=%s) for tenant %s with verification token", domain, customDomain.id,
                tenantId);

        return Response.status(Response.Status.CREATED).entity(toDomainDto(customDomain)).build();
    }

    /**
     * Get details for a specific custom domain.
     *
     * @param domainId
     *            UUID of the domain
     * @return Domain DTO with full verification status
     */
    @GET
    @Path("/{domainId}")
    @Timed(
            value = "custom_domains.get",
            description = "Time to get custom domain details")
    @RolesAllowed({"ADMIN", "PLATFORM_ADMIN"})
    public Response getDomain(@PathParam("domainId") UUID domainId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        CustomDomain domain = CustomDomain.find("id = ?1 AND tenant.id = ?2", domainId, tenantId).firstResult();

        if (domain == null) {
            throw new NotFoundException("Custom domain not found or does not belong to current tenant");
        }

        return Response.ok(toDomainDto(domain)).build();
    }

    /**
     * Remove a custom domain from the current tenant.
     *
     * <p>
     * This immediately removes the domain from the database. The corresponding Kubernetes Certificate resource should
     * be deleted manually or by a cleanup job.
     *
     * @param domainId
     *            UUID of the domain to remove
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{domainId}")
    @Transactional
    @Timed(
            value = "custom_domains.delete",
            description = "Time to delete custom domain")
    @RolesAllowed({"ADMIN", "PLATFORM_ADMIN"})
    public Response removeDomain(@PathParam("domainId") UUID domainId) {
        UUID tenantId = TenantContext.getCurrentTenantId();

        CustomDomain domain = CustomDomain.find("id = ?1 AND tenant.id = ?2", domainId, tenantId).firstResult();

        if (domain == null) {
            throw new NotFoundException("Custom domain not found or does not belong to current tenant");
        }

        String domainName = domain.domain;
        domain.delete();

        LOG.infof("Deleted custom domain %s (id=%s) for tenant %s", domainName, domainId, tenantId);

        // TODO: Fire CustomDomainRemoved event to trigger cache invalidation + K8s cleanup
        return Response.noContent().build();
    }

    /**
     * Convert CustomDomain entity to DTO for API responses.
     */
    private CustomDomainDto toDomainDto(CustomDomain domain) {
        return new CustomDomainDto(domain.id, domain.domain, domain.status.name(), domain.verified, domain.dnsChallenge,
                domain.verificationErrorMessage, domain.certificateExpiry, domain.createdAt);
    }

    /** Request DTO for adding a custom domain */
    public static class AddDomainRequest {
        @NotBlank(
                message = "Domain name is required")
        @jakarta.validation.constraints.Pattern(
                regexp = "^[a-zA-Z0-9][a-zA-Z0-9-.]{0,251}[a-zA-Z0-9]$",
                flags = Flag.CASE_INSENSITIVE,
                message = "Invalid domain format")
        public String domain;
    }

    /** Response DTO for custom domain details */
    public record CustomDomainDto(UUID id, String domain, String status, Boolean verified, Object dnsChallenge,
            String verificationErrorMessage, OffsetDateTime certificateExpiry, OffsetDateTime createdAt) {
    }

    /** Error response DTO */
    public record ErrorResponse(String message) {
    }
}
