package ai.nobi.mcp.ga4;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * An immutable GA4 event containing only explicitly supplied scalar metadata.
 * Callers must exclude personal information, credentials, prompts, and raw tool arguments.
 * Boolean parameters are encoded as numeric 1 or 0 for GA4 custom metrics.
 */
public final class AnalyticsEvent {
    private static final int MaxNameLength = 40;
    private static final int MaxValueLength = 100;
    private static final int MaxIdentifierLength = 256;
    private static final int MaxParameters = 25;
    private static final Pattern NamePattern = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Set<String> ReservedEvents = Set.of(
            "ad_activeview", "ad_click", "ad_exposure", "ad_query", "ad_reward",
            "adunit_exposure", "app_clear_data", "app_exception", "app_install",
            "app_remove", "app_store_refund", "app_update", "app_upgrade",
            "dynamic_link_app_open", "dynamic_link_app_update", "dynamic_link_first_open",
            "error", "firebase_campaign", "firebase_in_app_message_action",
            "firebase_in_app_message_dismiss", "firebase_in_app_message_impression",
            "first_open", "first_visit", "notification_dismiss", "notification_foreground",
            "notification_open", "notification_receive", "notification_send", "os_update",
            "session_start", "user_engagement", "ad_impression", "in_app_purchase", "screen_view");

    private final String name;
    private final String clientId;
    private final Instant timestamp;
    private final Map<String, Object> parameters;
    private final String userId;

    /**
     * Creates an event without a cross-device user identifier.
     *
     * @param name the GA4 event name
     * @param clientId a stable anonymous client identifier, never a raw session token
     * @param timestamp the original occurrence time
     * @param parameters up to 25 GA4 scalar parameters
     * @throws IllegalArgumentException if a field exceeds GA4 limits
     */
    public AnalyticsEvent(String name, String clientId, Instant timestamp, Map<String, Object> parameters) {
        this(name, clientId, timestamp, parameters, null);
    }

    /**
     * Creates an event with an optional non-personal user identifier.
     *
     * @param name the GA4 event name
     * @param clientId a stable anonymous client identifier
     * @param timestamp the original occurrence time
     * @param parameters up to 25 GA4 scalar parameters
     * @param userId an optional non-personal authenticated user identifier
     * @throws IllegalArgumentException if a field exceeds GA4 limits
     */
    public AnalyticsEvent(
            String name,
            String clientId,
            Instant timestamp,
            Map<String, Object> parameters,
            String userId) {
        AnalyticsEvent.validateName(name);

        if (ReservedEvents.contains(name)) {
            throw new IllegalArgumentException("The event name is reserved by GA4.");
        }

        this.name = name;
        this.clientId = AnalyticsEvent.validateIdentifier(clientId);
        this.timestamp = Objects.requireNonNull(timestamp, "An event timestamp is required.");
        this.parameters = AnalyticsEvent.copyParameters(parameters);
        this.userId = userId == null ? null : AnalyticsEvent.validateIdentifier(userId);

        try {
            AnalyticsEvent.timestampMicros(timestamp);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The event timestamp is outside the supported range.");
        }
    }

    /** @return the event name */
    public String name() {
        return this.name;
    }

    /** @return the anonymous client identifier */
    public String clientId() {
        return this.clientId;
    }

    /** @return the original occurrence time */
    public Instant timestamp() {
        return this.timestamp;
    }

    /** @return immutable normalized scalar parameters */
    public Map<String, Object> parameters() {
        return this.parameters;
    }

    /** @return the optional non-personal user identifier, or null */
    public String userId() {
        return this.userId;
    }

    /** Converts an occurrence time to GA4's microsecond precision without overflowing. */
    static long timestampMicros(Instant timestamp) {
        return Math.addExact(Math.multiplyExact(timestamp.getEpochSecond(), 1_000_000), timestamp.getNano() / 1_000);
    }

    /** Validates the shared GA4 event and parameter name syntax. */
    private static void validateName(String name) {
        if (name == null || name.length() > MaxNameLength || !NamePattern.matcher(name).matches()) {
            throw new IllegalArgumentException("GA4 names must start with a letter and contain at most 40 letters, digits, or underscores.");
        }
    }

    /** Validates an identifier without including its value in an error. */
    private static String validateIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank() || identifier.length() > MaxIdentifierLength) {
            throw new IllegalArgumentException("A nonblank identifier of at most 256 characters is required.");
        }

        return identifier;
    }

    /** Copies and normalizes parameters so later caller mutations cannot change an event. */
    private static Map<String, Object> copyParameters(Map<String, Object> parameters) {
        Objects.requireNonNull(parameters, "Event parameters are required.");

        if (parameters.size() > MaxParameters) {
            throw new IllegalArgumentException("GA4 events support at most 25 parameters.");
        }

        Map<String, Object> copy = new LinkedHashMap<>();

        for (Map.Entry<String, Object> parameter : parameters.entrySet()) {
            String name = parameter.getKey();
            AnalyticsEvent.validateName(name);

            if (name.startsWith("firebase_") || name.startsWith("ga_") || name.startsWith("google_")) {
                throw new IllegalArgumentException("The parameter name uses a GA4 reserved prefix.");
            }

            copy.put(name, AnalyticsEvent.normalizeValue(parameter.getValue()));
        }

        return Collections.unmodifiableMap(copy);
    }

    /** Accepts immutable GA4 scalar values and normalizes booleans into numeric metrics. */
    private static Object normalizeValue(Object value) {
        if (value instanceof String text && text.codePointCount(0, text.length()) <= MaxValueLength) {
            return text;
        }

        if (value instanceof Boolean flag) {
            return flag ? 1 : 0;
        }

        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return value;
        }

        if ((value instanceof BigInteger || value instanceof BigDecimal) && Double.isFinite(((Number) value).doubleValue())) {
            return value;
        }

        if (value instanceof Double number && Double.isFinite(number)) {
            return value;
        }

        if (value instanceof Float number && Float.isFinite(number)) {
            return value;
        }

        throw new IllegalArgumentException("GA4 parameters must be finite numbers, booleans, or strings of at most 100 characters.");
    }
}
