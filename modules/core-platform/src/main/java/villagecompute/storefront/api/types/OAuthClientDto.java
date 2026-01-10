package villagecompute.storefront.api.types;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Data Transfer Object for OAuth Client.
 *
 * <p>
 * Represents an OAuth client credential in API responses. Client secret hash is NEVER exposed through this DTO for
 * security reasons.
 *
 * <p>
 * References:
 * <ul>
 * <li>Entity: {@link villagecompute.storefront.data.models.OAuthClient}</li>
 * <li>Task I5.T4: OAuth client management</li>
 * <li>Architecture: Section 5 Contract Patterns (OAuth scopes)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OAuthClientDto {

    public UUID id;

    public String clientId;

    public String name;

    public String description;

    public Set<String> scopes;

    public boolean active;

    public int rateLimitPerMinute;

    public OffsetDateTime createdAt;

    public OffsetDateTime updatedAt;

    public OffsetDateTime lastUsedAt;
}
