package villagecompute.storefront.headless.support;

import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import villagecompute.storefront.api.headless.HeadlessApiBinding;

/**
 * Test-only stub resource for exercising customer read/write scopes.
 */
@Path("/api/v1/headless/customer/profile")
@HeadlessApiBinding
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TestHeadlessCustomerResource {

    @GET
    public Response getProfile() {
        return Response.ok(Map.of("customerId", "test-customer", "status", "ok")).build();
    }

    @PATCH
    public Response updateProfile(Map<String, Object> payload) {
        return Response.ok(Map.of("customerId", "test-customer", "updated", true)).build();
    }
}
