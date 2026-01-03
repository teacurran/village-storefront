package villagecompute.storefront.api.rest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.MediaAssetListResponse;
import villagecompute.storefront.api.types.MediaAssetResponse;
import villagecompute.storefront.api.types.MediaDerivativeResponse;
import villagecompute.storefront.api.types.MediaDownloadUrlResponse;
import villagecompute.storefront.api.types.MediaUploadCompleteRequest;
import villagecompute.storefront.api.types.MediaUploadNegotiationRequest;
import villagecompute.storefront.api.types.MediaUploadNegotiationResponse;
import villagecompute.storefront.data.models.MediaAsset;
import villagecompute.storefront.data.models.MediaDerivative;
import villagecompute.storefront.media.exceptions.MediaAssetNotFoundException;
import villagecompute.storefront.media.exceptions.MediaDownloadLimitExceededException;
import villagecompute.storefront.media.exceptions.MediaQuotaExceededException;
import villagecompute.storefront.services.MediaService;

/**
 * REST endpoints for managing tenant media uploads, processing, and signed URLs.
 */
@Path("/api/v1/media")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class MediaResource {

    private static final Logger LOG = Logger.getLogger(MediaResource.class);

    @Inject
    MediaService mediaService;

    @POST
    @Path("/upload/negotiate")
    public Response negotiateUpload(@Valid MediaUploadNegotiationRequest request) {
        try {
            MediaService.UploadRequest uploadRequest = new MediaService.UploadRequest(request.getFilename(),
                    request.getContentType(), request.getFileSize(), request.getAssetType(),
                    request.getMaxDownloadAttempts());
            MediaService.MediaUploadNegotiation negotiation = mediaService.negotiateUpload(uploadRequest);
            OffsetDateTime expiresAt = OffsetDateTime.now().plus(negotiation.presignedUrl().getExpiry());

            MediaUploadNegotiationResponse response = new MediaUploadNegotiationResponse(negotiation.assetId(),
                    negotiation.storageKey(), negotiation.presignedUrl().getUrl(), expiresAt,
                    negotiation.remainingQuotaBytes());
            return Response.ok(response).build();
        } catch (MediaQuotaExceededException e) {
            return error(Response.Status.REQUEST_ENTITY_TOO_LARGE, e.getMessage(), e.getRemainingBytes());
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    @POST
    @Path("/{assetId}/complete")
    public Response completeUpload(@PathParam("assetId") UUID assetId, MediaUploadCompleteRequest request) {
        try {
            MediaAsset asset = mediaService.completeUpload(assetId,
                    request != null ? request.getChecksumSha256() : null);
            MediaAssetResponse response = toAssetResponse(asset, List.of());
            return Response.ok(response).build();
        } catch (MediaAssetNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    @GET
    public Response listAssets(@QueryParam("type") String assetType, @QueryParam("status") String status,
            @QueryParam("page") Integer page, @QueryParam("size") Integer size) {
        MediaService.MediaAssetPage pageResult = mediaService.listAssets(assetType, status, page != null ? page : 0,
                size != null ? size : 20);
        List<MediaAssetResponse> items = pageResult.assets().stream().map(asset -> toAssetResponse(asset, List.of()))
                .collect(Collectors.toList());
        MediaAssetListResponse response = new MediaAssetListResponse(items, pageResult.total(), pageResult.page(),
                pageResult.size());
        return Response.ok(response).build();
    }

    @GET
    @Path("/{assetId}")
    public Response getAsset(@PathParam("assetId") UUID assetId) {
        try {
            MediaService.MediaAssetView view = mediaService.getAsset(assetId);
            MediaAssetResponse response = toAssetResponse(view.asset(),
                    view.derivatives().stream().map(this::toDerivativeResponse).collect(Collectors.toList()));
            return Response.ok(response).build();
        } catch (MediaAssetNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        }
    }

    @DELETE
    @Path("/{assetId}")
    public Response deleteAsset(@PathParam("assetId") UUID assetId) {
        try {
            mediaService.deleteAsset(assetId);
            return Response.noContent().build();
        } catch (MediaAssetNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        }
    }

    @GET
    @Path("/{assetId}/download")
    public Response getSignedDownload(@PathParam("assetId") UUID assetId,
            @QueryParam("derivativeType") String derivativeType) {
        try {
            MediaService.MediaDownload download = mediaService.generateSignedUrl(assetId, derivativeType);
            MediaDownloadUrlResponse response = new MediaDownloadUrlResponse(download.url(), download.expiresAt(),
                    download.remainingAttempts());
            return Response.ok(response).build();
        } catch (MediaAssetNotFoundException e) {
            return error(Response.Status.NOT_FOUND, e.getMessage());
        } catch (MediaDownloadLimitExceededException e) {
            return error(Response.Status.FORBIDDEN, "Download limit reached");
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    private MediaAssetResponse toAssetResponse(MediaAsset asset, List<MediaDerivativeResponse> derivatives) {
        MediaAssetResponse response = new MediaAssetResponse();
        response.setId(asset.id);
        response.setAssetType(asset.assetType);
        response.setOriginalFilename(asset.originalFilename);
        response.setMimeType(asset.mimeType);
        response.setFileSize(asset.fileSize);
        response.setStatus(asset.status);
        response.setWidth(asset.width);
        response.setHeight(asset.height);
        response.setDurationSeconds(asset.durationSeconds);
        response.setCreatedAt(asset.createdAt);
        response.setUpdatedAt(asset.updatedAt);
        response.setDownloadAttempts(asset.downloadAttempts);
        response.setMaxDownloadAttempts(asset.maxDownloadAttempts);
        response.setDerivatives(derivatives);
        return response;
    }

    private MediaDerivativeResponse toDerivativeResponse(MediaDerivative derivative) {
        return new MediaDerivativeResponse(derivative.id, derivative.derivativeType, derivative.storageKey,
                derivative.mimeType, derivative.fileSize, derivative.width, derivative.height);
    }

    private Response error(Response.Status status, String message) {
        return error(status, message, null);
    }

    private Response error(Response.Status status, String message, Long remainingBytes) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("error", message);
        if (remainingBytes != null) {
            body.put("remainingQuotaBytes", remainingBytes);
        }
        LOG.warnf("Media API error (%s): %s", status, message);
        return Response.status(status).entity(body).build();
    }
}
