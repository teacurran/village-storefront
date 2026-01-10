package villagecompute.storefront.util;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;

import villagecompute.storefront.api.types.Money;

/**
 * Utility for formatting Money values for display in templates.
 *
 * <p>
 * Provides locale-aware formatting of monetary amounts with proper currency symbols, decimal places, and grouping.
 * Wraps the existing Money value object and outputs display-ready strings for Qute templates.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Money formatting helpers for storefront</li>
 * <li>UI/UX Architecture Section 4.6: Localization & Multi-Currency Display</li>
 * <li>API Types: Money value object</li>
 * </ul>
 */
@ApplicationScoped
public class MoneyFormatter {

    /**
     * Format a Money object for display with default locale (en-US).
     *
     * @param money
     *            money value object
     * @return formatted string (e.g., "$19.99")
     */
    public String format(Money money) {
        return format(money, Locale.US);
    }

    /**
     * Format a Money object for display with specified locale.
     *
     * @param money
     *            money value object
     * @param locale
     *            target locale for formatting
     * @return formatted string with currency symbol and amount
     */
    public String format(Money money, Locale locale) {
        if (money == null) {
            return "";
        }

        try {
            BigDecimal amount = money.getAmountAsDecimal();
            String currencyCode = money.getCurrency();

            Currency currency = Currency.getInstance(currencyCode);
            NumberFormat formatter = NumberFormat.getCurrencyInstance(locale);
            formatter.setCurrency(currency);

            return formatter.format(amount);
        } catch (Exception e) {
            // Fallback to simple format if currency/locale issues
            return money.getCurrency() + " " + money.getAmount();
        }
    }

    /**
     * Format a Money object for display in Spanish locale.
     *
     * @param money
     *            money value object
     * @return formatted string (e.g., "19,99 €" or "$19,99")
     */
    public String formatEs(Money money) {
        return format(money, new Locale("es", "ES"));
    }

    /**
     * Format amount and currency code into display string with default locale.
     *
     * @param amount
     *            amount as BigDecimal
     * @param currencyCode
     *            ISO 4217 currency code (e.g., "USD", "EUR")
     * @return formatted string
     */
    public String format(BigDecimal amount, String currencyCode) {
        return format(new Money(amount, currencyCode));
    }

    /**
     * Format amount and currency code into display string with specified locale.
     *
     * @param amount
     *            amount as BigDecimal
     * @param currencyCode
     *            ISO 4217 currency code
     * @param locale
     *            target locale
     * @return formatted string
     */
    public String format(BigDecimal amount, String currencyCode, Locale locale) {
        return format(new Money(amount, currencyCode), locale);
    }

    /**
     * Get just the currency symbol for a given currency code and locale.
     *
     * @param currencyCode
     *            ISO 4217 currency code
     * @param locale
     *            target locale
     * @return currency symbol (e.g., "$", "€", "£")
     */
    public String getCurrencySymbol(String currencyCode, Locale locale) {
        try {
            Currency currency = Currency.getInstance(currencyCode);
            return currency.getSymbol(locale);
        } catch (Exception e) {
            return currencyCode;
        }
    }

    /**
     * Format a price comparison showing both sale price and original price.
     *
     * @param salePrice
     *            current sale price
     * @param originalPrice
     *            original price (before discount)
     * @return formatted string like "$15.99 (was $19.99)"
     */
    public String formatPriceComparison(Money salePrice, Money originalPrice) {
        return formatPriceComparison(salePrice, originalPrice, Locale.US);
    }

    /**
     * Format a price comparison with locale support.
     *
     * @param salePrice
     *            current sale price
     * @param originalPrice
     *            original price
     * @param locale
     *            target locale
     * @return formatted comparison string
     */
    public String formatPriceComparison(Money salePrice, Money originalPrice, Locale locale) {
        if (salePrice == null || originalPrice == null) {
            return format(salePrice != null ? salePrice : originalPrice, locale);
        }

        String salePriceFormatted = format(salePrice, locale);
        String originalPriceFormatted = format(originalPrice, locale);

        return String.format("%s (was %s)", salePriceFormatted, originalPriceFormatted);
    }

    /**
     * Calculate and format savings amount.
     *
     * @param originalPrice
     *            original price
     * @param salePrice
     *            sale price
     * @return formatted savings (e.g., "Save $5.00")
     */
    public String formatSavings(Money originalPrice, Money salePrice) {
        return formatSavings(originalPrice, salePrice, Locale.US);
    }

    /**
     * Calculate and format savings amount with locale.
     *
     * @param originalPrice
     *            original price
     * @param salePrice
     *            sale price
     * @param locale
     *            target locale
     * @return formatted savings
     */
    public String formatSavings(Money originalPrice, Money salePrice, Locale locale) {
        if (originalPrice == null || salePrice == null) {
            return "";
        }

        BigDecimal savings = originalPrice.getAmountAsDecimal().subtract(salePrice.getAmountAsDecimal());
        if (savings.compareTo(BigDecimal.ZERO) <= 0) {
            return "";
        }

        Money savingsMoney = new Money(savings, originalPrice.getCurrency());
        return "Save " + format(savingsMoney, locale);
    }

    /**
     * Calculate discount percentage between two prices.
     *
     * @param originalPrice
     *            original price
     * @param salePrice
     *            sale price
     * @return discount percentage (e.g., "25%")
     */
    public String formatDiscountPercentage(Money originalPrice, Money salePrice) {
        if (originalPrice == null || salePrice == null) {
            return "";
        }

        BigDecimal original = originalPrice.getAmountAsDecimal();
        BigDecimal sale = salePrice.getAmountAsDecimal();

        if (original.compareTo(BigDecimal.ZERO) == 0) {
            return "";
        }

        BigDecimal discount = original.subtract(sale);
        BigDecimal percentage = discount.divide(original, 4, BigDecimal.ROUND_HALF_UP).multiply(new BigDecimal("100"));

        return String.format("-%d%%", percentage.intValue());
    }
}
