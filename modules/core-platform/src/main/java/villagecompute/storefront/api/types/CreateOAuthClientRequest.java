package villagecompute.storefront.api.types;

import java.util.Set;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request DTO for creating an OAuth client.
 *
 * <p>
 * Validates that required fields (name, scopes) are present and rate limit is within acceptable bounds.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I5.T4: OAuth client management</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateOAuthClientRequest {

    @NotBlank
    @Size(
            max = 255)
    public String name;

    @Size(
            max = 2000)
    public String description;

    @NotEmpty(
            message = "At least one scope is required")
    public Set<String> scopes;

    @Min(
            value = 100,
            message = "Rate limit must be at least 100 requests per minute")
    public Integer rateLimitPerMinute;
}
