package villagecompute.storefront.api.rest;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.CreateInventoryAdjustmentRequest;
import villagecompute.storefront.api.types.CreateInventoryLocationRequest;
import villagecompute.storefront.api.types.CreateInventoryTransferRequest;
import villagecompute.storefront.api.types.InventoryAdjustmentDto;
import villagecompute.storefront.api.types.InventoryLocationDto;
import villagecompute.storefront.api.types.InventoryTransferDto;
import villagecompute.storefront.data.models.AdjustmentReason;
import villagecompute.storefront.data.models.InventoryAdjustment;
import villagecompute.storefront.data.models.InventoryLocation;
import villagecompute.storefront.data.models.InventoryTransfer;
import villagecompute.storefront.data.models.InventoryTransferLine;
import villagecompute.storefront.data.models.ProductVariant;
import villagecompute.storefront.data.repositories.InventoryLocationRepository;
import villagecompute.storefront.services.InvalidLocationException;
import villagecompute.storefront.services.InventoryTransferService;
import villagecompute.storefront.services.mappers.InventoryMapper;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource for admin inventory management operations.
 *
 * <p>
 * Provides endpoints for multi-location inventory operations:
 * <ul>
 * <li>GET /api/v1/admin/inventory/locations - List locations</li>
 * <li>POST /api/v1/admin/inventory/locations - Create location</li>
 * <li>GET /api/v1/admin/inventory/transfers - List transfers</li>
 * <li>POST /api/v1/admin/inventory/transfers - Create transfer</li>
 * <li>GET /api/v1/admin/inventory/transfers/{id} - Get transfer details</li>
 * <li>POST /api/v1/admin/inventory/transfers/{id}/receive - Receive transfer</li>
 * <li>POST /api/v1/admin/inventory/adjustments - Record adjustment</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext and require admin authentication.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Multi-location inventory workflow</li>
 * <li>Architecture: Inventory transfer DTO contract</li>
 * <li>OpenAPI: /admin/inventory/** paths</li>
 * </ul>
 */
@Path("/api/v1/admin/inventory")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class InventoryAdminResource {

    private static final Logger LOG = Logger.getLogger(InventoryAdminResource.class);

    @Inject
    InventoryLocationRepository locationRepository;

    @Inject
    InventoryTransferService transferService;

    @Inject
    InventoryMapper inventoryMapper;

    // ========================================
    // Location Endpoints
    // ========================================

    /**
     * List all inventory locations.
     *
     * @return list of locations
     */
    @GET
    @Path("/locations")
    public Response listLocations() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/inventory/locations - tenantId=%s", tenantId);

        List<InventoryLocation> locations = locationRepository.findAllForTenant();
        List<InventoryLocationDto> dtos = locations.stream().map(inventoryMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * Create a new inventory location.
     *
     * @param request
     *            location creation request
     * @return created location
     */
    @POST
    @Path("/locations")
    public Response createLocation(@Valid CreateInventoryLocationRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/inventory/locations - tenantId=%s, code=%s", tenantId, request.getCode());

        // Check for duplicate code
        if (locationRepository.findByCode(request.getCode()).isPresent()) {
            return Response.status(Status.CONFLICT)
                    .entity("Location with code '" + request.getCode() + "' already exists").build();
        }

        InventoryLocation location = new InventoryLocation();
        location.code = request.getCode();
        location.name = request.getName();
        location.type = request.getType();
        location.address = request.getAddress();
        location.active = request.getActive() != null ? request.getActive() : true;

        locationRepository.persist(location);

        InventoryLocationDto dto = inventoryMapper.toDto(location);
        return Response.status(Status.CREATED).entity(dto).build();
    }

    // ========================================
    // Transfer Endpoints
    // ========================================

    /**
     * List all inventory transfers.
     *
     * @return list of transfers
     */
    @GET
    @Path("/transfers")
    public Response listTransfers() {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/inventory/transfers - tenantId=%s", tenantId);

        List<InventoryTransfer> transfers = transferService.getAllTransfers();
        List<InventoryTransferDto> dtos = transfers.stream().map(inventoryMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * Get transfer details by ID.
     *
     * @param transferId
     *            transfer UUID
     * @return transfer details
     */
    @GET
    @Path("/transfers/{transferId}")
    public Response getTransfer(@PathParam("transferId") UUID transferId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/inventory/transfers/%s - tenantId=%s", transferId, tenantId);

        try {
            InventoryTransfer transfer = transferService.getTransfer(transferId);
            InventoryTransferDto dto = inventoryMapper.toDto(transfer);
            return Response.ok(dto).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }

    /**
     * Create a new inventory transfer.
     *
     * @param request
     *            transfer creation request
     * @return created transfer with job ID
     */
    @POST
    @Path("/transfers")
    public Response createTransfer(@Valid CreateInventoryTransferRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/inventory/transfers - tenantId=%s, sourceLocationId=%s, destinationLocationId=%s",
                tenantId, request.getSourceLocationId(), request.getDestinationLocationId());

        try {
            // Load locations
            InventoryLocation sourceLocation = locationRepository.findByIdForTenant(request.getSourceLocationId())
                    .orElseThrow(() -> new InvalidLocationException(request.getSourceLocationId()));
            InventoryLocation destinationLocation = locationRepository
                    .findByIdForTenant(request.getDestinationLocationId())
                    .orElseThrow(() -> new InvalidLocationException(request.getDestinationLocationId()));

            // Build transfer entity
            InventoryTransfer transfer = new InventoryTransfer();
            transfer.sourceLocation = sourceLocation;
            transfer.destinationLocation = destinationLocation;
            transfer.initiatedBy = request.getInitiatedBy();
            transfer.expectedArrivalDate = request.getExpectedArrivalDate();
            transfer.carrier = request.getCarrier();
            transfer.trackingNumber = request.getTrackingNumber();
            transfer.shippingCost = request.getShippingCost();
            transfer.notes = request.getNotes();

            // Build transfer lines
            for (CreateInventoryTransferRequest.TransferLineRequest lineRequest : request.getLines()) {
                ProductVariant variant = ProductVariant.findById(lineRequest.getVariantId());
                if (variant == null) {
                    return Response.status(Status.BAD_REQUEST)
                            .entity("Variant not found: " + lineRequest.getVariantId()).build();
                }

                InventoryTransferLine line = new InventoryTransferLine();
                line.variant = variant;
                line.quantity = lineRequest.getQuantity();
                line.notes = lineRequest.getNotes();
                transfer.addLine(line);
            }

            // Create transfer (validates, reserves inventory, enqueues barcode job)
            InventoryTransfer createdTransfer = transferService.createTransfer(transfer);

            InventoryTransferDto dto = inventoryMapper.toDto(createdTransfer);
            return Response.status(Status.CREATED).entity(dto).build();

        } catch (InvalidLocationException e) {
            return Response.status(Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        }
    }

    /**
     * Mark transfer as received and update destination inventory.
     *
     * @param transferId
     *            transfer UUID
     * @return updated transfer
     */
    @POST
    @Path("/transfers/{transferId}/receive")
    public Response receiveTransfer(@PathParam("transferId") UUID transferId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/inventory/transfers/%s/receive - tenantId=%s", transferId, tenantId);

        try {
            InventoryTransfer transfer = transferService.receiveTransfer(transferId);
            InventoryTransferDto dto = inventoryMapper.toDto(transfer);
            return Response.ok(dto).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Status.NOT_FOUND).entity(e.getMessage()).build();
        } catch (IllegalStateException e) {
            return Response.status(Status.CONFLICT).entity(e.getMessage()).build();
        }
    }

    // ========================================
    // Adjustment Endpoints
    // ========================================

    /**
     * Record a manual inventory adjustment.
     *
     * @param request
     *            adjustment request
     * @return created adjustment record
     */
    @POST
    @Path("/adjustments")
    public Response createAdjustment(@Valid CreateInventoryAdjustmentRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/inventory/adjustments - tenantId=%s, variantId=%s, locationId=%s, change=%d", tenantId,
                request.getVariantId(), request.getLocationId(), request.getQuantityChange());

        try {
            AdjustmentReason reason = AdjustmentReason.valueOf(request.getReason().toUpperCase());

            InventoryAdjustment adjustment = transferService.recordAdjustment(request.getVariantId(),
                    request.getLocationId(), request.getQuantityChange(), reason, request.getAdjustedBy(),
                    request.getNotes());

            InventoryAdjustmentDto dto = inventoryMapper.toDto(adjustment);
            return Response.status(Status.CREATED).entity(dto).build();

        } catch (IllegalArgumentException e) {
            return Response.status(Status.BAD_REQUEST).entity(e.getMessage()).build();
        } catch (InvalidLocationException e) {
            return Response.status(Status.NOT_FOUND).entity(e.getMessage()).build();
        }
    }
}
