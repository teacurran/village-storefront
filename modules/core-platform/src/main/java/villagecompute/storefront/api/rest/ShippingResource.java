package villagecompute.storefront.api.rest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.jboss.logging.Logger;

import villagecompute.storefront.data.models.Order;
import villagecompute.storefront.data.models.ShippingLabel;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.LabelRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.LabelResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ServiceLevel;
import villagecompute.storefront.services.ShippingService;
import villagecompute.storefront.services.ShippingService.AggregatedRateResult;
import villagecompute.storefront.services.ShippingService.ShippingRateRequest;
import villagecompute.storefront.tenant.TenantContext;

/**
 * REST resource for shipping operations.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>POST /shipping/rates - Fetch shipping rates from carriers</li>
 * <li>POST /shipping/validate-address - Validate and normalize addresses</li>
 * <li>POST /shipping/labels - Create shipping labels</li>
 * <li>GET /shipping/labels/{id} - Retrieve label details</li>
 * </ul>
 *
 * <p>
 * All endpoints are tenant-scoped and include correlation tracking for observability.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T4: Shipping rate + label endpoints</li>
 * <li>Architecture §4: Integration Adapter Layer</li>
 * </ul>
 */
@Path("/api/v1/shipping")
@Produces(MediaType.APPLICATION_JSON)
public class ShippingResource {

    private static final Logger LOG = Logger.getLogger(ShippingResource.class);

    @Inject
    ShippingService shippingService;

    /**
     * Fetch shipping rates for a shipment.
     *
     * @param correlationId
     *            X-Correlation-ID header for tracing
     * @param request
     *            shipping rate request
     * @return available shipping rates from carriers
     */
    @POST
    @Path("/rates")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response getRates(@HeaderParam("X-Correlation-ID") String correlationId, @Valid RateRequestDTO request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        LOG.infof("POST /shipping/rates - tenantId=%s, correlationId=%s", tenantId, corrId);

        try {
            ShippingRateRequest serviceRequest = new ShippingRateRequest(
                    new Address(request.origin.street1, request.origin.street2, request.origin.city,
                            request.origin.state, request.origin.postalCode, request.origin.country,
                            request.origin.residential),
                    new Address(request.destination.street1, request.destination.street2, request.destination.city,
                            request.destination.state, request.destination.postalCode, request.destination.country,
                            request.destination.residential),
                    new villagecompute.storefront.integration.shipping.CarrierRateAdapter.Package(
                            request.packageInfo.weightOz, request.packageInfo.lengthIn, request.packageInfo.widthIn,
                            request.packageInfo.heightIn, request.packageInfo.declaredValue,
                            request.packageInfo.description),
                    request.serviceLevels.stream().map(ServiceLevel::valueOf).collect(Collectors.toList()));

            AggregatedRateResult result = shippingService.fetchRates(serviceRequest, corrId);

            List<RateDTO> rateDTOs = result.rates().stream()
                    .map(r -> new RateDTO(r.carrierCode(), r.serviceLevel().name(), r.serviceName(), r.cost(),
                            r.currency(), r.estimatedDays(), r.estimatedDelivery().toString()))
                    .collect(Collectors.toList());

            RateResponseDTO response = new RateResponseDTO(rateDTOs, result.fallbackUsed(), result.fallbackReason());

            return Response.ok(response).header("X-Correlation-ID", corrId).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to fetch shipping rates - tenantId=%s, correlationId=%s", tenantId, corrId);
            return Response
                    .status(Status.INTERNAL_SERVER_ERROR).entity(createProblemDetails("Internal Server Error",
                            "Failed to fetch shipping rates", Status.INTERNAL_SERVER_ERROR))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    /**
     * Validate and normalize a shipping address.
     *
     * @param correlationId
     *            X-Correlation-ID header for tracing
     * @param request
     *            address validation request
     * @return normalized address and validation status
     */
    @POST
    @Path("/validate-address")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response validateAddress(@HeaderParam("X-Correlation-ID") String correlationId,
            @Valid AddressValidationRequestDTO request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        LOG.infof("POST /shipping/validate-address - tenantId=%s, correlationId=%s", tenantId, corrId);

        try {
            AddressValidationRequest serviceRequest = new AddressValidationRequest(request.street1, request.street2,
                    request.city, request.state, request.postalCode, request.country, corrId);

            AddressValidationResult result = shippingService.validateAddress(serviceRequest, corrId);

            AddressValidationResponseDTO response = new AddressValidationResponseDTO(result.status().name(),
                    result.normalizedAddress() != null
                            ? new AddressDTO(result.normalizedAddress().street1(), result.normalizedAddress().street2(),
                                    result.normalizedAddress().city(), result.normalizedAddress().state(),
                                    result.normalizedAddress().postalCode(), result.normalizedAddress().country(),
                                    result.normalizedAddress().residential())
                            : null,
                    result.warnings(), result.errorMessage());

            return Response.ok(response).header("X-Correlation-ID", corrId).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to validate address - tenantId=%s, correlationId=%s", tenantId, corrId);
            return Response
                    .status(Status.INTERNAL_SERVER_ERROR).entity(createProblemDetails("Internal Server Error",
                            "Failed to validate address", Status.INTERNAL_SERVER_ERROR))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    /**
     * Create a shipping label for an order.
     *
     * @param correlationId
     *            X-Correlation-ID header for tracing
     * @param request
     *            label creation request
     * @return label details with tracking number and URL
     */
    @POST
    @Path("/labels")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createLabel(@HeaderParam("X-Correlation-ID") String correlationId, @Valid LabelRequestDTO request) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        String corrId = correlationId != null ? correlationId : UUID.randomUUID().toString();

        LOG.infof("POST /shipping/labels - tenantId=%s, orderId=%s, carrier=%s, correlationId=%s", tenantId,
                request.orderId, request.carrierCode, corrId);

        try {
            // Verify order exists and belongs to tenant
            Order order = Order.findById(request.orderId);
            if (order == null || !order.tenant.id.equals(tenantId)) {
                return Response.status(Status.NOT_FOUND)
                        .entity(createProblemDetails("Not Found", "Order not found", Status.NOT_FOUND))
                        .header("X-Correlation-ID", corrId).build();
            }

            LabelRequest serviceRequest = new LabelRequest(
                    new Address(request.origin.street1, request.origin.street2, request.origin.city,
                            request.origin.state, request.origin.postalCode, request.origin.country,
                            request.origin.residential),
                    new Address(request.destination.street1, request.destination.street2, request.destination.city,
                            request.destination.state, request.destination.postalCode, request.destination.country,
                            request.destination.residential),
                    new villagecompute.storefront.integration.shipping.CarrierRateAdapter.Package(
                            request.packageInfo.weightOz, request.packageInfo.lengthIn, request.packageInfo.widthIn,
                            request.packageInfo.heightIn, request.packageInfo.declaredValue,
                            request.packageInfo.description),
                    ServiceLevel.valueOf(request.serviceLevel), request.returnAddress, corrId, Map.of());

            LabelResult result = shippingService.createLabel(serviceRequest, request.carrierCode, corrId);

            // Persist label record
            ShippingLabel label = new ShippingLabel();
            label.order = order;
            label.carrierCode = request.carrierCode;
            label.serviceLevel = request.serviceLevel;
            label.trackingNumber = result.trackingNumber();
            label.labelUrl = result.labelUrl();
            label.cost = result.cost();
            label.currency = result.currency();
            label.estimatedDelivery = result.estimatedDelivery();
            label.correlationId = corrId;
            label.persist();

            LabelResponseDTO response = new LabelResponseDTO(label.id.toString(), result.trackingNumber(),
                    result.labelUrl(), result.cost(), result.currency(),
                    result.estimatedDelivery() != null ? result.estimatedDelivery().toString() : null);

            return Response.status(Status.CREATED).entity(response).header("X-Correlation-ID", corrId).build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.warnf("Label creation failed - %s - tenantId=%s, correlationId=%s", e.getMessage(), tenantId, corrId);
            return Response.status(Status.BAD_REQUEST)
                    .entity(createProblemDetails("Bad Request", e.getMessage(), Status.BAD_REQUEST))
                    .header("X-Correlation-ID", corrId).build();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create shipping label - tenantId=%s, correlationId=%s", tenantId, corrId);
            return Response
                    .status(Status.INTERNAL_SERVER_ERROR).entity(createProblemDetails("Internal Server Error",
                            "Failed to create shipping label", Status.INTERNAL_SERVER_ERROR))
                    .header("X-Correlation-ID", corrId).build();
        }
    }

    /**
     * Retrieve shipping label details.
     *
     * @param labelId
     *            label UUID
     * @return label details
     */
    @GET
    @Path("/labels/{labelId}")
    public Response getLabel(@PathParam("labelId") UUID labelId) {
        UUID tenantId = TenantContext.getCurrentTenantId();
        LOG.infof("GET /shipping/labels/%s - tenantId=%s", labelId, tenantId);

        ShippingLabel label = ShippingLabel.findById(labelId);
        if (label == null || !label.tenant.id.equals(tenantId)) {
            return Response.status(Status.NOT_FOUND)
                    .entity(createProblemDetails("Not Found", "Shipping label not found", Status.NOT_FOUND)).build();
        }

        LabelResponseDTO response = new LabelResponseDTO(label.id.toString(), label.trackingNumber, label.labelUrl,
                label.cost, label.currency,
                label.estimatedDelivery != null ? label.estimatedDelivery.toString() : null);

        return Response.ok(response).build();
    }

    private Map<String, Object> createProblemDetails(String title, String detail, Status status) {
        Map<String, Object> problem = new HashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status.getStatusCode());
        problem.put("detail", detail);
        return problem;
    }

    // DTOs for request/response

    public static class RateRequestDTO {
        public AddressDTO origin;
        public AddressDTO destination;
        public PackageDTO packageInfo;
        public List<String> serviceLevels;
    }

    public static class AddressDTO {
        public String street1;
        public String street2;
        public String city;
        public String state;
        public String postalCode;
        public String country;
        public boolean residential;

        public AddressDTO() {
        }

        public AddressDTO(String street1, String street2, String city, String state, String postalCode, String country,
                boolean residential) {
            this.street1 = street1;
            this.street2 = street2;
            this.city = city;
            this.state = state;
            this.postalCode = postalCode;
            this.country = country;
            this.residential = residential;
        }
    }

    public static class PackageDTO {
        public BigDecimal weightOz;
        public BigDecimal lengthIn;
        public BigDecimal widthIn;
        public BigDecimal heightIn;
        public BigDecimal declaredValue;
        public String description;
    }

    public static class RateResponseDTO {
        public List<RateDTO> rates;
        public boolean fallbackUsed;
        public String fallbackReason;

        public RateResponseDTO(List<RateDTO> rates, boolean fallbackUsed, String fallbackReason) {
            this.rates = rates;
            this.fallbackUsed = fallbackUsed;
            this.fallbackReason = fallbackReason;
        }
    }

    public static class RateDTO {
        public String carrierCode;
        public String serviceLevel;
        public String serviceName;
        public BigDecimal cost;
        public String currency;
        public Integer estimatedDays;
        public String estimatedDelivery;

        public RateDTO(String carrierCode, String serviceLevel, String serviceName, BigDecimal cost, String currency,
                Integer estimatedDays, String estimatedDelivery) {
            this.carrierCode = carrierCode;
            this.serviceLevel = serviceLevel;
            this.serviceName = serviceName;
            this.cost = cost;
            this.currency = currency;
            this.estimatedDays = estimatedDays;
            this.estimatedDelivery = estimatedDelivery;
        }
    }

    public static class AddressValidationRequestDTO {
        public String street1;
        public String street2;
        public String city;
        public String state;
        public String postalCode;
        public String country;
    }

    public static class AddressValidationResponseDTO {
        public String status;
        public AddressDTO normalizedAddress;
        public List<String> warnings;
        public String errorMessage;

        public AddressValidationResponseDTO(String status, AddressDTO normalizedAddress, List<String> warnings,
                String errorMessage) {
            this.status = status;
            this.normalizedAddress = normalizedAddress;
            this.warnings = warnings;
            this.errorMessage = errorMessage;
        }
    }

    public static class LabelRequestDTO {
        public UUID orderId;
        public String carrierCode;
        public String serviceLevel;
        public AddressDTO origin;
        public AddressDTO destination;
        public PackageDTO packageInfo;
        public String returnAddress;
    }

    public static class LabelResponseDTO {
        public String labelId;
        public String trackingNumber;
        public String labelUrl;
        public BigDecimal cost;
        public String currency;
        public String estimatedDelivery;

        public LabelResponseDTO(String labelId, String trackingNumber, String labelUrl, BigDecimal cost,
                String currency, String estimatedDelivery) {
            this.labelId = labelId;
            this.trackingNumber = trackingNumber;
            this.labelUrl = labelUrl;
            this.cost = cost;
            this.currency = currency;
            this.estimatedDelivery = estimatedDelivery;
        }
    }
}
