package villagecompute.storefront.api.rest;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.api.types.AddIntakeBatchItemsRequest;
import villagecompute.storefront.api.types.CommissionScheduleDto;
import villagecompute.storefront.api.types.ConsignmentItemDto;
import villagecompute.storefront.api.types.ConsignorDto;
import villagecompute.storefront.api.types.CreateCommissionScheduleRequest;
import villagecompute.storefront.api.types.CreateConsignmentItemRequest;
import villagecompute.storefront.api.types.CreateConsignorRequest;
import villagecompute.storefront.api.types.CreateIntakeBatchRequest;
import villagecompute.storefront.api.types.PayoutBatchDto;
import villagecompute.storefront.api.types.UpdateCommissionScheduleRequest;
import villagecompute.storefront.api.types.UpdateConsignmentItemRequest;
import villagecompute.storefront.data.models.CommissionSchedule;
import villagecompute.storefront.data.models.ConsignmentItem;
import villagecompute.storefront.data.models.Consignor;
import villagecompute.storefront.data.models.PayoutBatch;
import villagecompute.storefront.services.ConsignmentService;
import villagecompute.storefront.services.mappers.ConsignmentMapper;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource for admin consignment operations.
 *
 * <p>
 * Provides endpoints for managing consignors, consignment items, and payout batches:
 * <ul>
 * <li>GET /admin/consignors - List consignors</li>
 * <li>POST /admin/consignors - Create consignor</li>
 * <li>GET /admin/consignors/{id} - Get consignor details</li>
 * <li>PUT /admin/consignors/{id} - Update consignor</li>
 * <li>DELETE /admin/consignors/{id} - Delete consignor</li>
 * <li>GET /admin/consignments - List consignment items</li>
 * <li>POST /admin/consignments - Create consignment item</li>
 * <li>GET /admin/payouts - List payout batches</li>
 * <li>POST /admin/payouts - Create payout batch</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped via TenantContext and require admin authentication.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T1: Consignment REST endpoints</li>
 * <li>OpenAPI: /admin/consignors, /admin/consignments, /admin/payouts</li>
 * </ul>
 */
@Path("/api/v1/admin/consignors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConsignmentResource {

    private static final Logger LOG = Logger.getLogger(ConsignmentResource.class);

    @Inject
    ConsignmentService consignmentService;

    @Inject
    ConsignmentMapper consignmentMapper;

    @Inject
    villagecompute.storefront.services.BatchIntakeService batchIntakeService;

    // ========================================
    // Consignor Endpoints
    // ========================================

    /**
     * List active consignors.
     *
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of consignors
     */
    @GET
    public Response listConsignors(@QueryParam("page") int page, @QueryParam("size") int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/consignors - tenantId=%s, page=%d, size=%d", tenantId, page, size);

        int pageSize = size > 0 ? size : 20;
        int pageNumber = Math.max(page, 0);
        List<Consignor> consignors = consignmentService.listActiveConsignors(pageNumber, pageSize);
        List<ConsignorDto> dtos = consignors.stream().map(consignmentMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    // ========================================
    // Commission Schedule Endpoints
    // ========================================

    @GET
    @Path("/{consignorId}/commission-schedules")
    public Response listCommissionSchedules(@PathParam("consignorId") UUID consignorId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/consignors/%s/commission-schedules - tenantId=%s", consignorId, tenantId);

        List<CommissionSchedule> schedules = consignmentService.getAllCommissionSchedules(consignorId);
        List<CommissionScheduleDto> dtos = schedules.stream().map(consignmentMapper::toDto)
                .collect(Collectors.toList());
        return Response.ok(dtos).build();
    }

    @POST
    @Path("/{consignorId}/commission-schedules")
    public Response createCommissionSchedule(@PathParam("consignorId") UUID consignorId,
            @Valid CreateCommissionScheduleRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors/%s/commission-schedules - tenantId=%s", consignorId, tenantId);

        try {
            CommissionSchedule schedule = new CommissionSchedule();
            schedule.categoryId = request.getCategoryId();
            schedule.commissionRate = request.getCommissionRate();
            schedule.effectiveFrom = request.getEffectiveFrom();
            schedule.effectiveUntil = request.getEffectiveUntil();
            schedule.priority = request.getPriority() != null ? request.getPriority() : 0;
            schedule.notes = request.getNotes();
            schedule.metadata = request.getMetadata();
            schedule.status = "active";
            schedule.createdBy = "admin-api";

            CommissionSchedule created = consignmentService.createCommissionSchedule(consignorId, schedule);
            return Response.status(Status.CREATED).entity(consignmentMapper.toDto(created)).build();

        } catch (IllegalArgumentException e) {
            Status status = Status.BAD_REQUEST;
            if (isNotFoundError(e.getMessage())) {
                status = Status.NOT_FOUND;
            }
            return Response.status(status)
                    .entity(createProblemDetails(status == Status.NOT_FOUND ? "Not Found" : "Bad Request",
                            e.getMessage(), status))
                    .build();
        }
    }

    @PUT
    @Path("/{consignorId}/commission-schedules/{scheduleId}")
    public Response updateCommissionSchedule(@PathParam("consignorId") UUID consignorId,
            @PathParam("scheduleId") UUID scheduleId, @Valid UpdateCommissionScheduleRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/consignors/%s/commission-schedules/%s - tenantId=%s", consignorId, scheduleId, tenantId);

        try {
            CommissionSchedule updates = new CommissionSchedule();
            updates.categoryId = request.getCategoryId();
            updates.commissionRate = request.getCommissionRate();
            updates.effectiveFrom = request.getEffectiveFrom();
            updates.effectiveUntil = request.getEffectiveUntil();
            updates.priority = request.getPriority();
            updates.notes = request.getNotes();
            updates.metadata = request.getMetadata();
            updates.status = request.getStatus();

            CommissionSchedule updated = consignmentService.updateCommissionSchedule(scheduleId, updates);
            return Response.ok(consignmentMapper.toDto(updated)).build();

        } catch (IllegalArgumentException e) {
            Status status = isNotFoundError(e.getMessage()) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            return Response.status(status)
                    .entity(createProblemDetails(status == Status.NOT_FOUND ? "Not Found" : "Bad Request",
                            e.getMessage(), status))
                    .build();
        }
    }

    /**
     * Create a new consignor.
     *
     * @param request
     *            create consignor request
     * @return created consignor DTO
     */
    @POST
    public Response createConsignor(@Valid CreateConsignorRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors - tenantId=%s, name=%s", tenantId, request.getName());

        try {
            Consignor consignor = new Consignor();
            consignor.name = request.getName();
            consignor.contactInfo = request.getContactInfo();
            consignor.payoutSettings = request.getPayoutSettings();
            consignor.status = "active";

            Consignor created = consignmentService.createConsignor(consignor, request.getTaxId(),
                    request.getTaxIdKeyVersion());
            ConsignorDto dto = consignmentMapper.toDto(created);

            return Response.status(Status.CREATED).entity(dto).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid create consignor request - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST)).build();
        }
    }

    /**
     * Get consignor by ID.
     *
     * @param id
     *            consignor UUID
     * @return consignor DTO
     */
    @GET
    @Path("/{id}")
    public Response getConsignor(@PathParam("id") UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/consignors/%s - tenantId=%s", id, tenantId);

        Optional<Consignor> consignor = consignmentService.getConsignor(id);
        if (consignor.isEmpty()) {
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", "Consignor not found", Status.NOT_FOUND)).build();
        }

        ConsignorDto dto = consignmentMapper.toDto(consignor.get());
        return Response.ok(dto).build();
    }

    /**
     * Update consignor.
     *
     * @param id
     *            consignor UUID
     * @param request
     *            update consignor request
     * @return updated consignor DTO
     */
    @PUT
    @Path("/{id}")
    public Response updateConsignor(@PathParam("id") UUID id, @Valid CreateConsignorRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/consignors/%s - tenantId=%s", id, tenantId);

        try {
            Consignor updates = new Consignor();
            updates.name = request.getName();
            updates.contactInfo = request.getContactInfo();
            updates.payoutSettings = request.getPayoutSettings();
            updates.status = "active";

            Consignor updated = consignmentService.updateConsignor(id, updates, request.getTaxId(),
                    request.getTaxIdKeyVersion());
            ConsignorDto dto = consignmentMapper.toDto(updated);

            return Response.ok(dto).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Consignor not found - tenantId=%s, id=%s", tenantId, id);
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", e.getMessage(), Status.NOT_FOUND)).build();
        }
    }

    /**
     * Delete consignor (soft delete).
     *
     * @param id
     *            consignor UUID
     * @return 204 No Content
     */
    @DELETE
    @Path("/{id}")
    public Response deleteConsignor(@PathParam("id") UUID id) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("DELETE /admin/consignors/%s - tenantId=%s", id, tenantId);

        try {
            consignmentService.deleteConsignor(id);
            return Response.noContent().build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Consignor not found - tenantId=%s, id=%s", tenantId, id);
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", e.getMessage(), Status.NOT_FOUND)).build();
        }
    }

    // ========================================
    // Consignment Item Endpoints
    // ========================================

    /**
     * List consignment items for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of consignment items
     */
    @GET
    @Path("/{consignorId}/items")
    public Response listConsignmentItems(@PathParam("consignorId") UUID consignorId, @QueryParam("page") int page,
            @QueryParam("size") int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/consignors/%s/items - tenantId=%s, page=%d, size=%d", consignorId, tenantId, page, size);

        int pageSize = size > 0 ? size : 20;
        int pageNumber = Math.max(page, 0);
        List<ConsignmentItem> items = consignmentService.getConsignorItems(consignorId, pageNumber, pageSize);
        List<ConsignmentItemDto> dtos = items.stream().map(consignmentMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * Create a consignment item (intake).
     *
     * @param request
     *            create consignment item request
     * @return created consignment item DTO
     */
    @POST
    @Path("/{consignorId}/items")
    public Response createConsignmentItem(@PathParam("consignorId") UUID consignorId,
            @Valid CreateConsignmentItemRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors/%s/items - tenantId=%s, productId=%s", consignorId, tenantId,
                request.getProductId());

        try {
            if (request.getProductId() == null) {
                throw new IllegalArgumentException("Product ID is required");
            }
            if (request.getConsignorId() != null && !request.getConsignorId().equals(consignorId)) {
                throw new IllegalArgumentException("Consignor ID mismatch between path and payload");
            }

            ConsignmentItem created = consignmentService.createConsignmentItem(consignorId, request.getProductId(),
                    request.getVariantId(), request.getCommissionRate(), request.getCostBasis(), request.getStatus());
            ConsignmentItemDto dto = consignmentMapper.toDto(created);

            return Response.status(Status.CREATED).entity(dto).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid create consignment item request - tenantId=%s, error=%s", tenantId, e.getMessage());
            Status status = isNotFoundError(e.getMessage()) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)).build();
        }
    }

    /**
     * Update consignment item commission/cost basis/status.
     */
    @PUT
    @Path("/{consignorId}/items/{itemId}")
    public Response updateConsignmentItem(@PathParam("consignorId") UUID consignorId, @PathParam("itemId") UUID itemId,
            @Valid UpdateConsignmentItemRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("PUT /admin/consignors/%s/items/%s - tenantId=%s", consignorId, itemId, tenantId);

        try {
            ConsignmentItem updates = new ConsignmentItem();
            updates.commissionRate = request.getCommissionRate();
            updates.status = request.getStatus();
            updates.costBasis = request.getCostBasis();

            ConsignmentItem updated = consignmentService.updateConsignmentItem(itemId, updates);
            return Response.ok(consignmentMapper.toDto(updated)).build();

        } catch (IllegalArgumentException e) {
            Status status = isNotFoundError(e.getMessage()) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            return Response.status(status)
                    .entity(createProblemDetails(status == Status.NOT_FOUND ? "Not Found" : "Bad Request",
                            e.getMessage(), status))
                    .build();
        }
    }

    // ========================================
    // Intake Batch Endpoints
    // ========================================

    /**
     * Create a new intake batch for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param request
     *            create intake batch request
     * @return created intake batch
     */
    @POST
    @Path("/{consignorId}/intake-batches")
    public Response createIntakeBatch(@PathParam("consignorId") UUID consignorId,
            @Valid CreateIntakeBatchRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors/%s/intake-batches - tenantId=%s", consignorId, tenantId);

        try {
            villagecompute.storefront.data.models.IntakeBatch batch = batchIntakeService.createIntakeBatch(consignorId,
                    request.getBatchName(), request.getNotes());

            Map<String, Object> response = new HashMap<>();
            response.put("id", batch.id);
            response.put("batchName", batch.batchName);
            response.put("status", batch.status);
            response.put("createdAt", batch.createdAt);

            return Response.status(Status.CREATED).entity(response).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid create intake batch request - tenantId=%s, error=%s", tenantId, e.getMessage());
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST)).build();
        }
    }

    /**
     * Add items to an intake batch.
     *
     * @param consignorId
     *            consignor UUID
     * @param batchId
     *            batch UUID
     * @param request
     *            add items request
     * @return list of created items
     */
    @POST
    @Path("/{consignorId}/intake-batches/{batchId}/items")
    public Response addItemsToBatch(@PathParam("consignorId") UUID consignorId, @PathParam("batchId") UUID batchId,
            @Valid AddIntakeBatchItemsRequest request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors/%s/intake-batches/%s/items - tenantId=%s, itemCount=%d", consignorId, batchId,
                tenantId, request.getItems().size());

        try {
            List<villagecompute.storefront.services.BatchIntakeService.IntakeItemRequest> itemRequests = request
                    .getItems().stream()
                    .map(item -> new villagecompute.storefront.services.BatchIntakeService.IntakeItemRequest(
                            item.getProductName(), item.getDescription(), item.getSku(), item.getBarcode(),
                            item.getPrice(), item.getCompareAtPrice(), item.getCostBasis(), item.getCommissionRate(),
                            item.getQuantity(), item.getVariantTitle(),
                            item.getMetadata() != null ? item.getMetadata() : new HashMap<>()))
                    .collect(Collectors.toList());

            List<ConsignmentItem> createdItems = batchIntakeService.addItemsToBatch(batchId, itemRequests);

            return Response.ok(createdItems.stream().map(consignmentMapper::toDto).collect(Collectors.toList()))
                    .build();

        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf("Invalid add items to batch request - tenantId=%s, error=%s", tenantId, e.getMessage());
            Status status = isNotFoundError(e.getMessage()) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)).build();
        }
    }

    /**
     * Complete an intake batch.
     *
     * @param consignorId
     *            consignor UUID
     * @param batchId
     *            batch UUID
     * @return completed batch
     */
    @POST
    @Path("/{consignorId}/intake-batches/{batchId}/complete")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response completeBatch(@PathParam("consignorId") UUID consignorId, @PathParam("batchId") UUID batchId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors/%s/intake-batches/%s/complete - tenantId=%s", consignorId, batchId, tenantId);

        try {
            villagecompute.storefront.data.models.IntakeBatch batch = batchIntakeService.completeBatch(batchId);

            Map<String, Object> response = new HashMap<>();
            response.put("id", batch.id);
            response.put("status", batch.status);
            response.put("totalItems", batch.totalItems);
            response.put("processedItems", batch.processedItems);
            response.put("completedAt", batch.completedAt);

            return Response.ok(response).build();

        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf("Invalid complete batch request - tenantId=%s, error=%s", tenantId, e.getMessage());
            Status status = isNotFoundError(e.getMessage()) ? Status.NOT_FOUND : Status.BAD_REQUEST;
            String title = status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)).build();
        }
    }

    // ========================================
    // Payout Batch Endpoints
    // ========================================

    /**
     * List payout batches for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param page
     *            page number (0-indexed)
     * @param size
     *            page size
     * @return list of payout batches
     */
    @GET
    @Path("/{consignorId}/payouts")
    public Response listPayoutBatches(@PathParam("consignorId") UUID consignorId, @QueryParam("page") int page,
            @QueryParam("size") int size) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /admin/consignors/%s/payouts - tenantId=%s, page=%d, size=%d", consignorId, tenantId, page,
                size);

        int pageSize = size > 0 ? size : 20;
        int pageNumber = Math.max(page, 0);
        List<PayoutBatch> batches = consignmentService.getConsignorPayoutBatches(consignorId, pageNumber, pageSize);
        List<PayoutBatchDto> dtos = batches.stream().map(consignmentMapper::toDto).collect(Collectors.toList());

        return Response.ok(dtos).build();
    }

    /**
     * Create a payout batch for a consignor.
     *
     * @param consignorId
     *            consignor UUID
     * @param periodStart
     *            period start date
     * @param periodEnd
     *            period end date
     * @return created payout batch DTO
     */
    @POST
    @Path("/{consignorId}/payouts")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    public Response createPayoutBatch(@PathParam("consignorId") UUID consignorId,
            @QueryParam("periodStart") String periodStart, @QueryParam("periodEnd") String periodEnd) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("POST /admin/consignors/%s/payouts - tenantId=%s, period=%s to %s", consignorId, tenantId,
                periodStart, periodEnd);

        if (periodStart == null || periodEnd == null) {
            return Response.status(Status.BAD_REQUEST).entity(createProblemDetails("Bad Request",
                    "Both periodStart and periodEnd are required", Status.BAD_REQUEST)).build();
        }

        try {
            LocalDate start = LocalDate.parse(periodStart);
            LocalDate end = LocalDate.parse(periodEnd);

            PayoutBatch batch = consignmentService.createPayoutBatch(consignorId, start, end);
            PayoutBatchDto dto = consignmentMapper.toDto(batch);

            return Response.status(Status.CREATED).entity(dto).build();

        } catch (java.time.format.DateTimeParseException e) {
            LOG.warnf("Invalid date format for payout batch - tenantId=%s, value=%s", tenantId, e.getParsedString());
            return Response.status(Status.BAD_REQUEST).entity(createProblemDetails("Bad Request",
                    "Dates must be formatted as ISO-8601 (YYYY-MM-DD)", Status.BAD_REQUEST)).build();

        } catch (IllegalArgumentException e) {
            LOG.warnf("Invalid create payout batch request - tenantId=%s, error=%s", tenantId, e.getMessage());
            Status status;
            if (isConflictError(e.getMessage())) {
                status = Status.CONFLICT;
            } else if (isNotFoundError(e.getMessage())) {
                status = Status.NOT_FOUND;
            } else {
                status = Status.BAD_REQUEST;
            }
            String title = status == Status.CONFLICT ? "Conflict"
                    : status == Status.NOT_FOUND ? "Not Found" : "Bad Request";
            return Response.status(status).entity(createProblemDetails(title, e.getMessage(), status)).build();
        }
    }

    // ========================================
    // Utility Methods
    // ========================================

    private boolean isNotFoundError(String message) {
        return message != null && message.toLowerCase().contains("not found");
    }

    private boolean isConflictError(String message) {
        return message != null && message.toLowerCase().contains("already exists");
    }

    /**
     * Create RFC 7807 Problem Details error response.
     *
     * @param title
     *            error title
     * @param detail
     *            error detail message
     * @param status
     *            HTTP status code
     * @return problem details object
     */
    private Map<String, Object> createProblemDetails(String title, String detail, Status status) {
        Map<String, Object> problem = new HashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.getStatusCode());
        if (detail != null && !detail.isBlank()) {
            problem.put("detail", detail);
        }
        return problem;
    }
}
