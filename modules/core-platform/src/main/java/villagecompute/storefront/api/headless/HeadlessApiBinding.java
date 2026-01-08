package villagecompute.storefront.api.headless;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Name binding annotation for headless API endpoints.
 *
 * <p>
 * Marks JAX-RS resources/methods that require OAuth client credentials authentication and rate limiting via
 * {@link HeadlessAuthFilter}.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * {@code
 * &#64;Path("/api/v1/headless/catalog")
 * &#64;HeadlessApiBinding
 * public class HeadlessCatalogResource {
 *     // Methods here will be protected by HeadlessAuthFilter
 * }
 * }
 * </pre>
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T7: Headless API OAuth authentication</li>
 * <li>JAX-RS: NameBinding for targeted filter application</li>
 * </ul>
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface HeadlessApiBinding {
}
