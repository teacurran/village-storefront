package villagecompute.storefront.services;

import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationRequest;
import villagecompute.storefront.integration.shipping.CarrierRateAdapter.AddressValidationResult;

/**
 * Adapter interface for address validation providers.
 *
 * <p>
 * Provides abstraction over address validation services (USPS, Lob, SmartyStreets, etc.) enabling provider switching
 * and multi-provider fallback strategies. Implementations should handle provider-specific API calls, response mapping,
 * and error handling.
 *
 * <p>
 * This interface delegates to {@link villagecompute.storefront.integration.shipping.CarrierRateAdapter} contract types
 * for request/response consistency across shipping and address validation operations.
 *
 * <p>
 * Future providers can implement this interface and register via CDI {@code @Alternative} or programmatic selection.
 * The {@link ShippingService} already orchestrates multi-provider fallback using
 * {@link villagecompute.storefront.integration.shipping.CarrierRateAdapter} implementations.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I3.T2: Address validation adapter abstraction for checkout orchestrator</li>
 * <li>Architecture §4: Integration Adapter Layer patterns</li>
 * <li>CarrierRateAdapter: Existing address validation contract</li>
 * </ul>
 */
public interface AddressValidatorAdapter {

    /**
     * Validate and normalize a shipping address.
     *
     * @param request
     *            address validation request with street, city, state, postal code
     * @return validation result with normalized address or error details
     * @throws RuntimeException
     *             if provider API call fails (implementation should convert provider-specific exceptions)
     */
    AddressValidationResult validateAddress(AddressValidationRequest request);

    /**
     * Check if this validator is currently available for use.
     *
     * <p>
     * Implementations should return {@code false} if provider API keys are missing, circuit breaker is open, or
     * provider is in maintenance mode. The orchestration layer uses this to skip unavailable validators.
     *
     * @return {@code true} if validator can process requests
     */
    boolean isAvailable();

    /**
     * Get the provider name for logging and metrics.
     *
     * @return provider identifier (e.g., "USPS", "Lob", "SmartyStreets")
     */
    String getProviderName();
}
