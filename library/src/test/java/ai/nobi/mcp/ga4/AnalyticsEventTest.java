package ai.nobi.mcp.ga4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Verifies public event and configuration contracts before any network delivery. */
class AnalyticsEventTest {
    @Test
    void freezesParametersAndPreservesNumericTypes() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("count", 3L);
        parameters.put("value", new BigDecimal("19.95"));
        parameters.put("success", true);
        parameters.put("bot", false);
        AnalyticsEvent event = new AnalyticsEvent("mcp_request", "anonymous", Instant.now(), parameters, "user_123");
        parameters.put("count", 9L);

        assertEquals(3L, event.parameters().get("count"));
        assertEquals(new BigDecimal("19.95"), event.parameters().get("value"));
        assertEquals(1, event.parameters().get("success"));
        assertEquals(0, event.parameters().get("bot"));
        assertEquals("user_123", event.userId());
        assertThrows(UnsupportedOperationException.class, () -> event.parameters().put("new", "value"));
    }

    @Test
    void rejectsInvalidNamesReservedNamesAndParameterLimits() {
        for (String name : new String[] {"", "1event", "with spaces", "a".repeat(41), "session_start", "screen_view"}) {
            assertThrows(IllegalArgumentException.class, () -> new AnalyticsEvent(name, "client", Instant.now(), Map.of()));
        }

        for (String name : new String[] {"ga_value", "firebase_conversion", "google_test", "_private"}) {
            assertThrows(IllegalArgumentException.class, () -> event(Map.of(name, 1)));
        }

        Map<String, Object> parameters = new LinkedHashMap<>();

        for (int index = 0; index < 25; index++) {
            parameters.put("field_" + index, index);
        }

        assertEquals(25, event(parameters).parameters().size());
        parameters.put("overflow", 1);
        assertThrows(IllegalArgumentException.class, () -> event(parameters));
    }

    @Test
    void enforcesStringAndNumericLimitsWithoutAcceptingMutableValues() {
        assertEquals("x".repeat(100), event(Map.of("text", "x".repeat(100))).parameters().get("text"));
        assertEquals("😀".repeat(100), event(Map.of("text", "😀".repeat(100))).parameters().get("text"));
        assertThrows(IllegalArgumentException.class, () -> event(Map.of("text", "x".repeat(101))));
        assertThrows(IllegalArgumentException.class, () -> event(Map.of("nested", Map.of("secret", "not-allowed"))));
        assertThrows(IllegalArgumentException.class, () -> event(Map.of("number", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> event(Map.of("number", Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> event(Map.of("number", new AtomicInteger(1))));
        assertThrows(IllegalArgumentException.class, () -> event(Map.of("number", BigInteger.TEN.pow(400))));
    }

    @Test
    void validatesIdentifiersAndTimestampOverflowWithoutExposingValues() {
        assertThrows(IllegalArgumentException.class, () -> new AnalyticsEvent("mcp_request", "", Instant.now(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnalyticsEvent("mcp_request", "x".repeat(257), Instant.now(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new AnalyticsEvent("mcp_request", "client", Instant.MAX, Map.of()));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> event(Map.of("private", "sensitive".repeat(100))));
        assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test
    void restrictsEndpointsAndValidatesBoundedSettings() {
        for (String endpoint : new String[] {
                "http://example.com/mp/collect", "https://user:password@example.com/mp/collect",
                "https://example.com/mp/collect?api_secret=hidden", "https://example.com/mp/collect#secret",
                "file:///tmp/events", "http://127.0.0.2/mp/collect"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> config().endpoint(URI.create(endpoint)).build());
            assertFalse(failure.getMessage().contains("hidden"));
            assertFalse(failure.getMessage().contains("password"));
        }

        assertTrue(config().endpoint(URI.create("https://region1.google-analytics.com/mp/collect")).build().endpoint().isAbsolute());
        assertTrue(config().endpoint(URI.create("http://127.0.0.1:1234/mp/collect")).build().endpoint().isAbsolute());
        assertThrows(IllegalArgumentException.class, () -> config().queueCapacity(0).build());
        assertThrows(IllegalArgumentException.class, () -> config().requestTimeout(Duration.ZERO).build());
        assertThrows(IllegalArgumentException.class, () -> config().shutdownTimeout(Duration.ofSeconds(-1)).build());
        assertThrows(IllegalArgumentException.class, () -> config().requestTimeout(Duration.ofSeconds(Long.MAX_VALUE)).build());
        assertThrows(IllegalArgumentException.class, () -> config().measurementId("invalid").build());
        assertThrows(IllegalArgumentException.class, () -> config().apiSecret(" ").build());
        assertFalse(config().build().toString().contains("fixture-secret"));
    }

    /** Creates a valid test event. */
    private static AnalyticsEvent event(Map<String, Object> parameters) {
        return new AnalyticsEvent("mcp_request", "client", Instant.now(), parameters);
    }

    /** Creates settings with fake credentials that never contact Google. */
    private static Ga4Config.Builder config() {
        return Ga4Config.builder().measurementId("G-TEST123").apiSecret("fixture-secret");
    }
}
