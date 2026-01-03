package villagecompute.storefront.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import villagecompute.storefront.api.types.Money;

class QuteExtensionsTest {

    @Test
    void formattedMoneyUsesDefaultLocale() {
        Money money = new Money(new BigDecimal("10.00"), "USD");
        assertEquals("$10.00", QuteExtensions.formatted(money));
        assertEquals("", QuteExtensions.formatted((Money) null));
    }

    @Test
    void formattedDateProducesReadableString() {
        OffsetDateTime date = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        assertEquals("Jan 1, 2026", QuteExtensions.formatted(date));
        assertTrue(QuteExtensions.formattedWithTime(date).contains("Jan 1, 2026"));
        assertEquals("", QuteExtensions.formatted((OffsetDateTime) null));
        assertEquals("", QuteExtensions.formattedWithTime(null));
    }

    @Test
    void stringUtilitiesWorkAsExpected() {
        assertEquals("Hel...", QuteExtensions.truncate("HelloWorld", 6));
        assertEquals("Hi", QuteExtensions.truncate("Hi", 10));
        assertTrue(QuteExtensions.isPresent("hello"));
        assertTrue(!QuteExtensions.isPresent("   "));
        assertEquals("JD", QuteExtensions.initials("John Doe"));
        assertEquals("", QuteExtensions.initials(" "));
        assertEquals("", QuteExtensions.initials(null));
        assertEquals("stories", QuteExtensions.pluralize("story", 2));
        assertEquals("bus", QuteExtensions.pluralize("bus", 1));
        assertEquals("buses", QuteExtensions.pluralize("bus", 2));
        assertEquals("brushes", QuteExtensions.pluralize("brush", 3));
    }
}
