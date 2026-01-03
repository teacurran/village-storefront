package villagecompute.storefront.api.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class HealthResourceTest {

    @Test
    void testHealthEndpoint() {
        given().when().get("/api/v1/health").then().statusCode(200).body("status", is("ok"));
    }
}
