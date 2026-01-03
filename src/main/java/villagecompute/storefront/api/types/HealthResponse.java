package villagecompute.storefront.api.types;

/**
 * Response type for the health check endpoint.
 */
public record HealthResponse(String status) {

    /**
     * Creates a healthy response.
     */
    public static HealthResponse ok() {
        return new HealthResponse("ok");
    }
}
