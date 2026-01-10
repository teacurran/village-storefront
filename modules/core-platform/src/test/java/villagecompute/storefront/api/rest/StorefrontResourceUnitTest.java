package villagecompute.storefront.api.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import villagecompute.storefront.data.models.Product;

class StorefrontResourceUnitTest {

    @Test
    void isNewProductEvaluatesCreationDate() throws Exception {
        StorefrontResource resource = new StorefrontResource();
        Product recent = new Product();
        recent.createdAt = OffsetDateTime.now();

        Product oldProduct = new Product();
        oldProduct.createdAt = OffsetDateTime.now().minusDays(60);

        Method method = StorefrontResource.class.getDeclaredMethod("isNewProduct", Product.class);
        method.setAccessible(true);

        assertTrue((Boolean) method.invoke(resource, recent));
        assertFalse((Boolean) method.invoke(resource, oldProduct));
        assertFalse((Boolean) method.invoke(resource, new Product()));
    }
}
