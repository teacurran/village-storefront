package villagecompute.storefront.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import villagecompute.storefront.api.types.Money;

class MoneyFormatterTest {

    private final MoneyFormatter formatter = new MoneyFormatter();

    @Test
    void formatReturnsLocalizedCurrency() {
        Money money = new Money(new BigDecimal("19.99"), "USD");
        assertEquals("$19.99", formatter.format(money));
        assertEquals("19,99 US$", formatter.format(money, new Locale("es", "ES")));
    }

    @Test
    void formatSavingsHandlesPositiveDifference() {
        Money original = new Money(new BigDecimal("29.00"), "USD");
        Money sale = new Money(new BigDecimal("21.50"), "USD");

        assertEquals("Save $7.50", formatter.formatSavings(original, sale));
        assertEquals("-25%", formatter.formatDiscountPercentage(original, sale));
    }

    @Test
    void handlesNullsAndEdgeCases() {
        assertEquals("", formatter.format(null));
        Money original = new Money(new BigDecimal("10.00"), "USD");
        Money sale = new Money(new BigDecimal("12.00"), "USD");

        assertEquals("", formatter.formatSavings(original, sale));
        assertEquals("", formatter.formatDiscountPercentage(new Money(BigDecimal.ZERO, "USD"), sale));

        assertEquals("120,00 €", formatter.format(new BigDecimal("120"), "EUR", Locale.GERMANY));
        Money invalid = new Money(new BigDecimal("5"), "ZZZ");
        assertEquals("ZZZ 5.00", formatter.format(invalid));
        assertEquals("", formatter.formatSavings(null, sale));
    }

    @Test
    void priceComparisonDisplaysBothValues() {
        Money original = new Money(new BigDecimal("29.00"), "USD");
        Money sale = new Money(new BigDecimal("21.50"), "USD");

        String comparison = formatter.formatPriceComparison(sale, original);
        assertTrue(comparison.contains("$21.50"));
        assertTrue(comparison.contains("$29.00"));
    }

    @Test
    void additionalEdgeCasesAreHandled() {
        assertEquals("ZZZ", formatter.getCurrencySymbol("ZZZ", Locale.US));

        Money sale = new Money(new BigDecimal("10.00"), "USD");
        assertEquals("$10.00", formatter.formatPriceComparison(sale, null));
        assertEquals("$10.00", formatter.formatPriceComparison(null, sale));

        Money free = new Money(new BigDecimal("0.00"), "USD");
        assertEquals("", formatter.formatDiscountPercentage(free, sale));
        assertEquals("", formatter.formatDiscountPercentage(sale, null));
    }
}
