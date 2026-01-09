package villagecompute.storefront.featureflags;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Marks a component as being guarded by a specific feature flag.
 *
 * <p>
 * The annotation is primarily used for documentation and runtime discovery so new payment processors can re-use the
 * same kill switches. Tooling may scan for this annotation to build flag inventories.
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface FeatureFlagged {

    /**
     * Feature flag key (e.g., {@code payments.stripe.enabled}).
     */
    String value();

    /**
     * Optional description that clarifies how the flag affects the component.
     */
    String description() default "";
}
