package villagecompute.storefront.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/**
 * Verifies shipping cache TTL configuration aligns with 15-minute requirement (Architecture §3 Rulebook).
 */
class ShippingCacheConfigurationTest {

    @Test
    void shippingRateCacheTtl_is15Minutes() throws IOException {
        Path propertiesPath = Path.of("src/main/resources/application.properties");
        assertTrue(Files.exists(propertiesPath), "application.properties must exist in main resources");

        try (InputStream input = Files.newInputStream(propertiesPath)) {
            assertNotNull(input, "application.properties should be readable");
            Properties properties = new Properties();
            properties.load(input);
            String ttl = properties.getProperty("quarkus.cache.caffeine.\"shipping-rate-cache\".expire-after-write");
            assertEquals("PT15M", ttl, "Shipping rate cache TTL must remain 15 minutes");
        }
    }
}
