package villagecompute.storefront.integration.shipping;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Core carrier rate adapter abstraction for fetching shipping rates, validating addresses, and creating shipping
 * labels. Implementations wrap external carrier APIs (USPS, UPS, FedEx) behind a uniform interface to enable provider
 * switching without modifying business logic.
 *
 * All methods are tenant-aware via TenantContext and return provider-agnostic result types. Implementations must handle
 * retries, timeouts, and circuit-breaking internally per blueprint §3.0 Rulebook.
 *
 * References:
 * <ul>
 * <li>Task I3.T4: Carrier adapter integration with retry/backoff</li>
 * <li>Architecture §4: Integration Adapter Layer timeout/retry policies</li>
 * <li>PaymentProvider: Interface pattern for external integrations</li>
 * </ul>
 */
public interface CarrierRateAdapter {

    /**
     * Fetch shipping rates for a given shipment request.
     *
     * @param request
     *            Shipping rate request parameters
     * @return Result containing available rates or fallback indication
     */
    RateResult fetchRates(RateRequest request);

    /**
     * Validate and normalize a postal address via carrier API.
     *
     * @param request
     *            Address validation request
     * @return Validation result with normalized address or error details
     */
    AddressValidationResult validateAddress(AddressValidationRequest request);

    /**
     * Create a shipping label for a confirmed shipment.
     *
     * @param request
     *            Label creation request
     * @return Result with label URL, tracking number, and carrier metadata
     */
    LabelResult createLabel(LabelRequest request);

    /**
     * Get carrier identifier (USPS, UPS, FEDEX).
     *
     * @return carrier code
     */
    String getCarrierCode();

    /**
     * Check if carrier adapter is currently available (health check).
     *
     * @return true if carrier API is reachable
     */
    boolean isAvailable();

    /**
     * Shipping rate request parameters.
     */
    record RateRequest(Address origin, Address destination, Package shipmentPackage, List<ServiceLevel> serviceLevels,
            String correlationId, Map<String, String> metadata) {
    }

    /**
     * Address for origin or destination.
     */
    record Address(String street1, String street2, String city, String state, String postalCode, String country,
            boolean residential) {
    }

    /**
     * Package dimensions and weight.
     */
    record Package(BigDecimal weightOz, BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn,
            BigDecimal declaredValue, String description) {
    }

    /**
     * Service level enum (e.g., GROUND, EXPRESS, OVERNIGHT).
     */
    enum ServiceLevel {
        GROUND, TWO_DAY, NEXT_DAY_AIR, PRIORITY, FIRST_CLASS, MEDIA_MAIL
    }

    /**
     * Result of rate fetch operation.
     */
    record RateResult(RateStatus status, List<Rate> rates, String errorMessage, Map<String, String> providerMetadata) {
    }

    /**
     * Individual shipping rate option.
     */
    record Rate(String carrierCode, ServiceLevel serviceLevel, String serviceName, BigDecimal cost, String currency,
            Integer estimatedDays, OffsetDateTime estimatedDelivery) {
    }

    /**
     * Rate fetch status.
     */
    enum RateStatus {
        OK, FALLBACK_USED, PROVIDER_DOWN, INVALID_REQUEST, RATE_LIMIT_EXCEEDED
    }

    /**
     * Address validation request.
     */
    record AddressValidationRequest(String street1, String street2, String city, String state, String postalCode,
            String country, String correlationId) {
    }

    /**
     * Address validation result.
     */
    record AddressValidationResult(ValidationStatus status, Address normalizedAddress, List<String> warnings,
            String errorMessage, Map<String, String> providerMetadata) {
    }

    /**
     * Validation status.
     */
    enum ValidationStatus {
        VALID, CORRECTED, INVALID, PROVIDER_UNAVAILABLE
    }

    /**
     * Label creation request.
     */
    record LabelRequest(Address origin, Address destination, Package shipmentPackage, ServiceLevel serviceLevel,
            String returnAddress, String correlationId, Map<String, String> metadata) {
    }

    /**
     * Label creation result.
     */
    record LabelResult(LabelStatus status, String trackingNumber, String labelUrl, BigDecimal cost, String currency,
            OffsetDateTime estimatedDelivery, String errorMessage, Map<String, String> providerMetadata) {
    }

    /**
     * Label creation status.
     */
    enum LabelStatus {
        CREATED, FAILED, PROVIDER_UNAVAILABLE
    }
}
