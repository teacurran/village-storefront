package villagecompute.storefront.util;

import java.util.Map;

import jakarta.ws.rs.core.Response;

/**
 * Utility class for creating RFC 7807 Problem Details error responses.
 *
 * <p>
 * Provides consistent error response format across all REST resources. All fields follow RFC 7807 Problem Details
 * specification.
 *
 * <p>
 * References:
 * <ul>
 * <li>RFC 7807: Problem Details for HTTP APIs</li>
 * <li>Task I3.T2: Extract shared ProblemDetails utility</li>
 * <li>OpenAPI: ProblemDetails component schema</li>
 * </ul>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7807">RFC 7807</a>
 */
public final class ProblemDetailsUtil {

    private ProblemDetailsUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Create a Problem Details error response map.
     *
     * @param title
     *            short human-readable summary of the problem type
     * @param detail
     *            specific explanation of this error occurrence
     * @param status
     *            HTTP status code
     * @return Map containing RFC 7807 Problem Details fields
     */
    public static Map<String, Object> createProblemDetails(String title, String detail, int status) {
        return Map.of("type", "about:blank", // Standard type for generic errors
                "title", title, "status", status, "detail", detail);
    }

    /**
     * Create a Problem Details error response map with validation errors.
     *
     * @param title
     *            short human-readable summary of the problem type
     * @param detail
     *            specific explanation of this error occurrence
     * @param status
     *            HTTP status code
     * @param errors
     *            field-level validation errors (field name → error messages)
     * @return Map containing RFC 7807 Problem Details fields including validation errors
     */
    public static Map<String, Object> createProblemDetails(String title, String detail, int status,
            Map<String, Object> errors) {
        return Map.of("type", "about:blank", "title", title, "status", status, "detail", detail, "errors", errors);
    }

    /**
     * Create a 400 Bad Request problem details response.
     *
     * @param detail
     *            specific explanation of the validation error
     * @return Map containing Problem Details fields
     */
    public static Map<String, Object> badRequest(String detail) {
        return createProblemDetails("Bad Request", detail, Response.Status.BAD_REQUEST.getStatusCode());
    }

    /**
     * Create a 401 Unauthorized problem details response.
     *
     * @param detail
     *            specific explanation of the authentication error
     * @return Map containing Problem Details fields
     */
    public static Map<String, Object> unauthorized(String detail) {
        return createProblemDetails("Unauthorized", detail, Response.Status.UNAUTHORIZED.getStatusCode());
    }

    /**
     * Create a 404 Not Found problem details response.
     *
     * @param detail
     *            specific explanation of what was not found
     * @return Map containing Problem Details fields
     */
    public static Map<String, Object> notFound(String detail) {
        return createProblemDetails("Not Found", detail, Response.Status.NOT_FOUND.getStatusCode());
    }

    /**
     * Create a 409 Conflict problem details response.
     *
     * @param detail
     *            specific explanation of the conflict
     * @return Map containing Problem Details fields
     */
    public static Map<String, Object> conflict(String detail) {
        return createProblemDetails("Conflict", detail, Response.Status.CONFLICT.getStatusCode());
    }

    /**
     * Create a 500 Internal Server Error problem details response.
     *
     * @param detail
     *            specific explanation of the server error
     * @return Map containing Problem Details fields
     */
    public static Map<String, Object> internalServerError(String detail) {
        return createProblemDetails("Internal Server Error", detail,
                Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
    }

    /**
     * Create a 503 Service Unavailable problem details response.
     *
     * @param detail
     *            specific explanation of why the service is unavailable
     * @return Map containing Problem Details fields
     */
    public static Map<String, Object> serviceUnavailable(String detail) {
        return createProblemDetails("Service Unavailable", detail, Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
    }
}
