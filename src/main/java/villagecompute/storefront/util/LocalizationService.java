package villagecompute.storefront.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Loads localized storefront messages from {@code messages/messages*.properties} and exposes them as simple maps that
 * Qute templates can consume.
 */
@ApplicationScoped
public class LocalizationService {

    private static final String BUNDLE_BASE_NAME = "messages.messages";

    /**
     * Load messages for the provided IETF language tag (e.g., {@code en}, {@code es}).
     *
     * @param localeTag
     *            language tag
     * @return immutable map of message keys to localized strings
     */
    public Map<String, String> loadMessages(String localeTag) {
        Locale locale = localeTag != null ? Locale.forLanguageTag(localeTag) : Locale.ENGLISH;
        Map<String, String> messages = new HashMap<>();
        populateBundle(messages, locale);
        if (!Locale.ENGLISH.equals(locale)) {
            // Fill any missing keys with English defaults
            populateBundle(messages, Locale.ENGLISH);
        }
        return Collections.unmodifiableMap(messages);
    }

    private void populateBundle(Map<String, String> messages, Locale locale) {
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale);
            bundle.keySet().forEach(key -> messages.putIfAbsent(key, bundle.getString(key)));
        } catch (MissingResourceException ignored) {
            // Ignore missing bundles - the call site will fall back to defaults.
        }
    }
}
