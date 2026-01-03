package villagecompute.storefront.api.rest;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import villagecompute.storefront.api.types.HealthResponse;

/**
 * Health check endpoint for the Village Storefront API.
 */
@Path("/api/v1/health")
public class HealthResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HealthResponse healthCheck() {
        return HealthResponse.ok();
    }
}
