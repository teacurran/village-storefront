package villagecompute.storefront.services;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import villagecompute.storefront.integration.shipping.CarrierRateAdapter;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Address;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.LabelRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.LabelResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.Rate;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.RateRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.RateResult;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.RateStatus;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.ServiceLevel;
import villagecompute.storefront.tenant.TenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.cache.CacheManager;

/**
 * Shipping service orchestrating address validation, rate fetching, and label creation across multiple carrier
 * adapters.
 *
 * <p>
 * Implements caching for shipping rates (15-minute TTL per §3.0 Rulebook) and fallback to table rates when carriers are
 * unavailable per §3.10 operational runbook. All operations are tenant-scoped and correlation-tracked for
 * observability.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T4: Shipping service with caching + fallback table rates</li>
 * <li>Architecture §3.0: Performance target <200ms, shipping rate cache 15 min</li>
 * <li>Architecture §3.10: Carrier API outage fallback procedures</li>
 * <li>Architecture §4: Integration Adapter Layer</li>
 * </ul>
 */
@ApplicationScoped
public class ShippingService {

    private static final Logger LOG = Logger.getLogger(ShippingService.class);

    @Inject
    Instance<CarrierRateAdapter> carrierAdapters;

    @Inject
    CacheManager cacheManager;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    FeatureToggle featureToggle;

    @ConfigProperty(
            name = "shipping.rates.enabled",
            defaultValue = "true")
    boolean ratesEnabled;

    @ConfigProperty(
            name = "shipping.fallback.enabled",
            defaultValue = "true")
    boolean fallbackEnabled;

    /**
     * Fetch shipping rates from all available carriers with caching.
     *
     * @param request
     *            rate request parameters
     * @param correlationId
     *            correlation ID for tracing
     * @return aggregated rate result
     */
    public AggregatedRateResult fetchRates(ShippingRateRequest request, String correlationId) {
        if (!ratesEnabled) {
            LOG.warnf("Shipping rates disabled via feature flag - correlationId=%s", correlationId);
            return new AggregatedRateResult(Collections.emptyList(), true, "Shipping rates temporarily disabled");
        }

        UUID tenantId = TenantContext.getCurrentTenantId();
        String cacheKey = buildRateCacheKey(tenantId, request);

        Timer.Sample sample = Timer.start(meterRegistry);

        AggregatedRateResult result = cacheManager.getCache("shipping-rate-cache").map(cache -> {
            AtomicBoolean cacheHit = new AtomicBoolean(true);
            AggregatedRateResult rates = (AggregatedRateResult) cache.get(cacheKey, k -> {
                cacheHit.set(false);
                LOG.debugf("Shipping rate cache miss - tenantId=%s, correlationId=%s", tenantId, correlationId);
                meterRegistry.counter("shipping.rate.cache.miss", "tenant_id", tenantId.toString()).increment();
                return fetchRatesFromCarriers(request, correlationId);
            }).await().indefinitely();

            if (cacheHit.get()) {
                LOG.debugf("Shipping rate cache hit - tenantId=%s, correlationId=%s", tenantId, correlationId);
                meterRegistry.counter("shipping.rate.cache.hit", "tenant_id", tenantId.toString()).increment();
            }
            return rates;
        }).orElseGet(() -> {
            LOG.warnf("Shipping rate cache unavailable, querying directly - tenantId=%s, correlationId=%s", tenantId,
                    correlationId);
            return fetchRatesFromCarriers(request, correlationId);
        });

        sample.stop(meterRegistry.timer("shipping.rate.fetch", "fallback", String.valueOf(result.fallbackUsed())));

        return result;
    }

    private AggregatedRateResult fetchRatesFromCarriers(ShippingRateRequest request, String correlationId) {
        List<Rate> allRates = new ArrayList<>();
        boolean anyCarrierDown = false;

        for (CarrierRateAdapter adapter : carrierAdapters) {
            if (!adapter.isAvailable()) {
                LOG.warnf("Carrier %s unavailable - correlationId=%s", adapter.getCarrierCode(), correlationId);
                anyCarrierDown = true;
                continue;
            }

            try {
                RateRequest adapterRequest = new RateRequest(request.origin(), request.destination(),
                        request.shipmentPackage(), request.serviceLevels(), correlationId, Map.of());

                RateResult result = adapter.fetchRates(adapterRequest);

                if (result.status() == RateStatus.OK) {
                    allRates.addAll(result.rates());
                } else {
                    LOG.warnf("Carrier %s returned non-OK status: %s - correlationId=%s", adapter.getCarrierCode(),
                            result.status(), correlationId);
                    anyCarrierDown = true;
                }
            } catch (Exception e) {
                LOG.errorf(e, "Error fetching rates from carrier %s - correlationId=%s", adapter.getCarrierCode(),
                        correlationId);
                anyCarrierDown = true;
            }
        }

        // If all carriers are down and fallback is enabled, use table rates
        if (allRates.isEmpty() && anyCarrierDown && fallbackEnabled) {
            LOG.warnf("All carriers unavailable, using fallback table rates - correlationId=%s", correlationId);
            allRates.addAll(getFallbackTableRates(request, correlationId));
            return new AggregatedRateResult(allRates, true, "Carriers unavailable, using fallback rates");
        }

        return new AggregatedRateResult(allRates, false, null);
    }

    private List<Rate> getFallbackTableRates(ShippingRateRequest request, String correlationId) {
        LOG.debugf("Generating fallback table rates - correlationId=%s", correlationId);

        // Simple flat-rate fallback based on package weight
        BigDecimal weight = request.shipmentPackage().weightOz();
        BigDecimal baseCost = weight.compareTo(new BigDecimal("16")) <= 0 ? new BigDecimal("5.99")
                : new BigDecimal("9.99");

        List<Rate> fallbackRates = new ArrayList<>();
        fallbackRates.add(new Rate("FALLBACK", ServiceLevel.GROUND, "Standard Shipping (Fallback)", baseCost, "USD", 7,
                OffsetDateTime.now().plusDays(7)));
        fallbackRates.add(new Rate("FALLBACK", ServiceLevel.PRIORITY, "Expedited Shipping (Fallback)",
                baseCost.multiply(new BigDecimal("2")), "USD", 3, OffsetDateTime.now().plusDays(3)));

        meterRegistry.counter("shipping.rate.fallback_used", "reason", "carrier_unavailable").increment();

        return fallbackRates;
    }

    /**
     * Validate and normalize a shipping address.
     *
     * @param request
     *            address validation request
     * @param correlationId
     *            correlation ID for tracing
     * @return validation result with normalized address
     */
    public AddressValidationResult validateAddress(AddressValidationRequest request, String correlationId) {
        LOG.debugf("Validating address - correlationId=%s, postalCode=%s", correlationId, request.postalCode());

        Timer.Sample sample = Timer.start(meterRegistry);

        // Try each carrier until one succeeds
        for (CarrierRateAdapter adapter : carrierAdapters) {
            if (!adapter.isAvailable()) {
                continue;
            }

            try {
                AddressValidationResult result = adapter.validateAddress(request);
                sample.stop(meterRegistry.timer("shipping.address.validate", "carrier", adapter.getCarrierCode(),
                        "status", result.status().name()));

                if (result.status() == CarrierRateAdapter.ValidationStatus.VALID
                        || result.status() == CarrierRateAdapter.ValidationStatus.CORRECTED) {
                    return result;
                }
            } catch (Exception e) {
                LOG.warnf(e, "Address validation failed for carrier %s - correlationId=%s", adapter.getCarrierCode(),
                        correlationId);
            }
        }

        // All carriers failed - return original address as-is with warning
        sample.stop(meterRegistry.timer("shipping.address.validate", "carrier", "NONE", "status", "UNAVAILABLE"));
        Address originalAddress = new Address(request.street1(), request.street2(), request.city(), request.state(),
                request.postalCode(), request.country(), false);

        return new AddressValidationResult(CarrierRateAdapter.ValidationStatus.PROVIDER_UNAVAILABLE, originalAddress,
                List.of("Address validation service unavailable"), "All carriers unavailable", Map.of());
    }

    /**
     * Create a shipping label for a confirmed shipment.
     *
     * @param request
     *            label creation request
     * @param carrierCode
     *            specific carrier to use
     * @param correlationId
     *            correlation ID for tracing
     * @return label result with tracking number and label URL
     */
    @Transactional
    public LabelResult createLabel(LabelRequest request, String carrierCode, String correlationId) {
        LOG.infof("Creating shipping label - carrier=%s, correlationId=%s", carrierCode, correlationId);

        Timer.Sample sample = Timer.start(meterRegistry);

        CarrierRateAdapter adapter = findAdapterByCarrierCode(carrierCode);
        if (adapter == null) {
            sample.stop(meterRegistry.timer("shipping.label.create", "carrier", carrierCode, "status", "ERROR"));
            throw new IllegalArgumentException("Unsupported carrier: " + carrierCode);
        }

        if (!adapter.isAvailable()) {
            sample.stop(meterRegistry.timer("shipping.label.create", "carrier", carrierCode, "status", "UNAVAILABLE"));
            throw new IllegalStateException("Carrier " + carrierCode + " is currently unavailable");
        }

        try {
            LabelResult result = adapter.createLabel(request);
            sample.stop(meterRegistry.timer("shipping.label.create", "carrier", carrierCode, "status",
                    result.status().name()));
            meterRegistry.counter("shipping.label.created", "carrier", carrierCode, "status", result.status().name())
                    .increment();

            return result;
        } catch (Exception e) {
            sample.stop(meterRegistry.timer("shipping.label.create", "carrier", carrierCode, "status", "ERROR"));
            LOG.errorf(e, "Label creation failed - carrier=%s, correlationId=%s", carrierCode, correlationId);
            throw new RuntimeException("Failed to create shipping label: " + e.getMessage(), e);
        }
    }

    /**
     * Invalidate shipping rate cache for a tenant.
     *
     * @param tenantId
     *            tenant identifier
     * @param reason
     *            reason for invalidation
     */
    public void invalidateRateCache(UUID tenantId, String reason) {
        cacheManager.getCache("shipping-rate-cache").ifPresent(cache -> {
            cache.invalidateAll().await().indefinitely();
            LOG.infof("Invalidated shipping rate cache - tenantId=%s, reason=%s", tenantId, reason);
            meterRegistry.counter("shipping.rate.cache.invalidated", "tenant_id", tenantId.toString()).increment();
        });
    }

    private CarrierRateAdapter findAdapterByCarrierCode(String carrierCode) {
        for (CarrierRateAdapter adapter : carrierAdapters) {
            if (adapter.getCarrierCode().equalsIgnoreCase(carrierCode)) {
                return adapter;
            }
        }
        return null;
    }

    private String buildRateCacheKey(UUID tenantId, ShippingRateRequest request) {
        String requestHash = hashRequest(request);
        return String.format("tenant:%s:rates:%s", tenantId, requestHash);
    }

    private String hashRequest(ShippingRateRequest request) {
        try {
            String input = String.format("%s|%s|%s|%s", request.origin().postalCode(),
                    request.destination().postalCode(), request.shipmentPackage().weightOz(),
                    request.serviceLevels().stream().map(Enum::name).collect(Collectors.joining(",")));

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            LOG.warnf("SHA-256 not available, using plaintext cache key");
            return String.valueOf(request.hashCode());
        }
    }

    /**
     * Shipping rate request parameters.
     */
    public record ShippingRateRequest(Address origin, Address destination, CarrierRateAdapter.Package shipmentPackage,
            List<ServiceLevel> serviceLevels) {
    }

    /**
     * Aggregated rate result from all carriers.
     */
    public record AggregatedRateResult(List<Rate> rates, boolean fallbackUsed, String fallbackReason) {
    }
}
