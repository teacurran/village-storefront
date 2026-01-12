package villagecompute.storefront.headless.support;

import java.util.Collections;
import java.util.Map;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import villagecompute.storefront.api.headless.HeadlessApiBinding;

/**
 * Test-only stub resource used to exercise orders scopes in
 * {@link villagecompute.storefront.headless.HeadlessScopeEnforcementIT}.
 */
@Path("/api/v1/headless/orders")
@HeadlessApiBinding
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TestHeadlessOrdersResource {

    @GET
    public Response listOrders() {
        return Response.ok(Map.of("orders", Collections.emptyList())).build();
    }

    @POST
    public Response createOrder(Map<String, Object> payload) {
        return Response.status(Response.Status.CREATED).entity(Map.of("id", "test-order", "status", "created")).build();
    }
}
