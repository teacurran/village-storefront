package villagecompute.storefront.api.rest;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import villagecompute.storefront.data.models.ShippingProfile;
import villagecompute.storefront.services.ShippingProfileService;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource exposing CRUD operations for shipping profiles.
 *
 * <p>
 * Shipping profiles configure carrier credentials, origin addresses, and default rate preferences per tenant.
 * </p>
 */
@Path("/api/v1/shipping/profiles")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ShippingProfileResource {

    private static final Logger LOG = Logger.getLogger(ShippingProfileResource.class);

    @Inject
    ShippingProfileService profileService;

    @Inject
    ObjectMapper objectMapper;

    @GET
    public Response listProfiles(@HeaderParam("X-Correlation-ID") String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = resolveCorrelationId(correlationId);
        LOG.infof("GET /shipping/profiles - tenantId=%s, correlationId=%s", tenantId, corrId);

        List<ShippingProfileResponse> responses = profileService.listProfiles(tenantId).stream().map(this::toResponse)
                .collect(Collectors.toList());
        return Response.ok(responses).header("X-Correlation-ID", corrId).build();
    }

    @GET
    @Path("/{profileId}")
    public Response getProfile(@PathParam("profileId") UUID profileId,
            @HeaderParam("X-Correlation-ID") String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = resolveCorrelationId(correlationId);
        LOG.infof("GET /shipping/profiles/%s - tenantId=%s, correlationId=%s", profileId, tenantId, corrId);

        return profileService.findProfile(tenantId, profileId)
                .map(profile -> Response.ok(toResponse(profile)).header("X-Correlation-ID", corrId).build())
                .orElseGet(() -> Response.status(Status.NOT_FOUND)
                        .entity(problem("Not Found", "Shipping profile not found", Status.NOT_FOUND))
                        .header("X-Correlation-ID", corrId).build());
    }

    @POST
    @Transactional
    public Response createProfile(@Valid ShippingProfileRequest request,
            @HeaderParam("X-Correlation-ID") String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = resolveCorrelationId(correlationId);
        LOG.infof("POST /shipping/profiles - tenantId=%s, correlationId=%s", tenantId, corrId);

        try {
            ShippingProfile profile = toEntity(request);
            ShippingProfile persisted = profileService.createProfile(tenantId, profile, request.isDefault);
            return Response.status(Status.CREATED).entity(toResponse(persisted)).header("X-Correlation-ID", corrId)
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST)
                    .entity(problem("Bad Request", e.getMessage(), Status.BAD_REQUEST))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    @PUT
    @Path("/{profileId}")
    @Transactional
    public Response updateProfile(@PathParam("profileId") UUID profileId, @Valid ShippingProfileRequest request,
            @HeaderParam("X-Correlation-ID") String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = resolveCorrelationId(correlationId);
        LOG.infof("PUT /shipping/profiles/%s - tenantId=%s, correlationId=%s", profileId, tenantId, corrId);

        try {
            ShippingProfile updates = toEntity(request);
            ShippingProfile updated = profileService.updateProfile(tenantId, profileId, updates, request.isDefault);
            return Response.ok(toResponse(updated)).header("X-Correlation-ID", corrId).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND).entity(problem("Not Found", e.getMessage(), Status.NOT_FOUND))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    @POST
    @Path("/{profileId}/default")
    @Transactional
    public Response setDefault(@PathParam("profileId") UUID profileId,
            @HeaderParam("X-Correlation-ID") String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = resolveCorrelationId(correlationId);
        LOG.infof("POST /shipping/profiles/%s/default - tenantId=%s, correlationId=%s", profileId, tenantId, corrId);

        try {
            ShippingProfile profile = profileService.markDefault(tenantId, profileId);
            return Response.ok(toResponse(profile)).header("X-Correlation-ID", corrId).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND).entity(problem("Not Found", e.getMessage(), Status.NOT_FOUND))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    @DELETE
    @Path("/{profileId}")
    @Transactional
    public Response deleteProfile(@PathParam("profileId") UUID profileId,
            @HeaderParam("X-Correlation-ID") String correlationId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = resolveCorrelationId(correlationId);
        LOG.infof("DELETE /shipping/profiles/%s - tenantId=%s, correlationId=%s", profileId, tenantId, corrId);

        try {
            profileService.deleteProfile(tenantId, profileId);
            return Response.noContent().header("X-Correlation-ID", corrId).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND).entity(problem("Not Found", e.getMessage(), Status.NOT_FOUND))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    private ShippingProfile toEntity(ShippingProfileRequest request) {
        ShippingProfile profile = new ShippingProfile();
        profile.name = request.name;
        profile.isDefault = request.isDefault;
        profile.enabledCarriers = String.join(",", request.enabledCarriers);
        profile.originAddress = writeJson(request.originAddress);
        profile.carrierCredentials = writeJson(
                request.carrierCredentials != null ? request.carrierCredentials : Collections.emptyMap());
        profile.metadata = writeJson(request.metadata != null ? request.metadata : Collections.emptyMap());
        profile.active = request.active;
        return profile;
    }

    private ShippingProfileResponse toResponse(ShippingProfile profile) {
        return new ShippingProfileResponse(profile.id, profile.name, profile.isDefault, profile.active,
                parseEnabledCarriers(profile.enabledCarriers), readJson(profile.originAddress),
                readJson(profile.carrierCredentials), readJson(profile.metadata), profile.createdAt, profile.updatedAt);
    }

    private List<String> parseEnabledCarriers(String carriers) {
        if (carriers == null || carriers.isBlank()) {
            return List.of();
        }
        return List.of(carriers.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private Map<String, Object> readJson(String json) {
        try {
            if (json == null || json.isBlank()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse profile JSON payload", e);
        }
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Collections.emptyMap());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON payload", e);
        }
    }

    private String resolveCorrelationId(String headerValue) {
        return headerValue != null && !headerValue.isBlank() ? headerValue : UUID.randomUUID().toString();
    }

    private Map<String, Object> problem(String title, String detail, Status status) {
        return Map.of("type", "about:blank", "title", title, "status", status.getStatusCode(), "detail",
                detail != null ? detail : "");
    }

    /**
     * Request DTO for creating/updating profiles.
     */
    public static class ShippingProfileRequest {
        @NotBlank
        public String name;

        public boolean isDefault;

        @NotEmpty
        public List<@NotBlank String> enabledCarriers;

        @NotNull public Map<String, Object> originAddress;

        public Map<String, Object> carrierCredentials;

        public Map<String, Object> metadata;

        public boolean active = true;
    }

    /**
     * Response DTO for shipping profile.
     */
    public static class ShippingProfileResponse {
        public UUID id;
        public String name;
        public boolean isDefault;
        public boolean active;
        public List<String> enabledCarriers;
        public Map<String, Object> originAddress;
        public Map<String, Object> carrierCredentials;
        public Map<String, Object> metadata;
        public OffsetDateTime createdAt;
        public OffsetDateTime updatedAt;

        public ShippingProfileResponse(UUID id, String name, boolean isDefault, boolean active,
                List<String> enabledCarriers, Map<String, Object> originAddress, Map<String, Object> carrierCredentials,
                Map<String, Object> metadata, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
            this.id = id;
            this.name = name;
            this.isDefault = isDefault;
            this.active = active;
            this.enabledCarriers = enabledCarriers;
            this.originAddress = originAddress;
            this.carrierCredentials = carrierCredentials;
            this.metadata = metadata;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
}
