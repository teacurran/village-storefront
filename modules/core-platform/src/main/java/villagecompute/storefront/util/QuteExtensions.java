package villagecompute.storefront.util;

import java.time.OffsetDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import villagecompute.storefront.api.types.Money;

import io.quarkus.qute.TemplateExtension;

/**
 * Qute template extensions for common formatting and utility functions.
 *
 * <p>
 * Provides template-level helpers for money formatting, date formatting, and other display utilities. These methods are
 * automatically available in all Qute templates via the @TemplateExtension mechanism.
 *
 * <p>
 * References:
 * <ul>
 * <li>Task I2.T2: Template helpers for storefront rendering</li>
 * <li>Quarkus Qute documentation: Template Extensions</li>
 * </ul>
 */
@ApplicationScoped
public class QuteExtensions {

    @Inject
    MoneyFormatter moneyFormatter;

    /**
     * Format Money object for display in templates.
     *
     * <p>
     * Usage in templates: {product.price.formatted}
     *
     * @param money
     *            money value
     * @return formatted money string
     */
    @TemplateExtension
    public static String formatted(Money money) {
        if (money == null) {
            return "";
        }
        // Use default US locale for now - will be enhanced with tenant locale later
        MoneyFormatter formatter = new MoneyFormatter();
        return formatter.format(money);
    }

    /**
     * Format OffsetDateTime for display in templates.
     *
     * <p>
     * Usage in templates: {order.createdAt.formatted}
     *
     * @param dateTime
     *            date/time value
     * @return formatted date string
     */
    @TemplateExtension
    public static String formatted(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
        return dateTime.format(formatter);
    }

    /**
     * Format OffsetDateTime with time for display.
     *
     * <p>
     * Usage in templates: {order.createdAt.formattedWithTime}
     *
     * @param dateTime
     *            date/time value
     * @return formatted date and time string
     */
    @TemplateExtension
    public static String formattedWithTime(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.US);
        return dateTime.format(formatter);
    }

    /**
     * Get current year for copyright notices.
     *
     * <p>
     * Usage in templates: {currentYear}
     *
     * @return current year as integer
     */
    public static int getCurrentYear() {
        return Year.now().getValue();
    }

    /**
     * Truncate string to specified length with ellipsis.
     *
     * <p>
     * Usage in templates: {description.truncate(100)}
     *
     * @param text
     *            text to truncate
     * @param length
     *            max length
     * @return truncated string
     */
    @TemplateExtension
    public static String truncate(String text, int length) {
        if (text == null || text.length() <= length) {
            return text;
        }
        return text.substring(0, length - 3) + "...";
    }

    /**
     * Check if a string is not null and not empty.
     *
     * <p>
     * Usage in templates: {#if description.isPresent}
     *
     * @param text
     *            text to check
     * @return true if text has content
     */
    @TemplateExtension
    public static boolean isPresent(String text) {
        return text != null && !text.trim().isEmpty();
    }

    /**
     * Generate initials from a name (useful for avatars).
     *
     * <p>
     * Usage in templates: {customer.name.initials}
     *
     * @param name
     *            full name
     * @return initials (e.g., "JD" for "John Doe")
     */
    @TemplateExtension
    public static String initials(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }

        String[] parts = name.trim().split("\\s+");
        StringBuilder initials = new StringBuilder();

        for (int i = 0; i < Math.min(2, parts.length); i++) {
            if (!parts[i].isEmpty()) {
                initials.append(parts[i].charAt(0));
            }
        }

        return initials.toString().toUpperCase();
    }

    /**
     * Pluralize a word based on count.
     *
     * <p>
     * Usage in templates: {"item".pluralize(count)}
     *
     * @param word
     *            singular word
     * @param count
     *            count for pluralization
     * @return singular or plural form
     */
    @TemplateExtension
    public static String pluralize(String word, int count) {
        if (count == 1) {
            return word;
        }
        // Simple English pluralization - can be enhanced
        if (word.endsWith("y")) {
            return word.substring(0, word.length() - 1) + "ies";
        } else if (word.endsWith("s") || word.endsWith("x") || word.endsWith("z") || word.endsWith("ch")
                || word.endsWith("sh")) {
            return word + "es";
        } else {
            return word + "s";
        }
    }
}
