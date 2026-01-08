package villagecompute.storefront.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LocalizationServiceTest {

    private final LocalizationService service = new LocalizationService();

    @Test
    void loadsEnglishMessagesByDefault() {
        Map<String, String> messages = service.loadMessages(null);
        assertEquals("Skip to main content", messages.get("skipToContent"));
    }

    @Test
    void loadsSpanishMessagesAndFallsBackToEnglish() {
        Map<String, String> messages = service.loadMessages("es");
        assertEquals("Saltar al contenido principal", messages.get("skipToContent"));
        assertTrue(messages.get("non_existent_key") == null);

        Map<String, String> fallback = service.loadMessages("fr");
        assertEquals("Skip to main content", fallback.get("skipToContent"));
    }
}
