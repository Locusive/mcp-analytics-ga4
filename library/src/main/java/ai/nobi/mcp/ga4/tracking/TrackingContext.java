package ai.nobi.mcp.ga4.tracking;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Carries optional attribution and correlation information without inventing a shopper identity. */
public final class TrackingContext {
    private static final String INSTALLATION_ID = (Integer.toUnsignedLong(UUID.randomUUID().hashCode()) + 1) + "." + Instant.now().getEpochSecond();
    private final String clientId;
    private final String userId;
    private final Map<String, Object> parameters;

    private TrackingContext(Builder builder) {
        this.clientId = builder.clientId == null ? INSTALLATION_ID : builder.clientId;
        this.userId = builder.userId;
        Map<String, Object> values = new LinkedHashMap<>(builder.parameters);
        values.put("identity_kind", builder.identifiedVisitor ? "identified" : "unidentified");
        this.parameters = Map.copyOf(values);
    }

    /**
     * Creates a context builder.
     *
     * @return The requested immutable context value.
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Returns the GA transport identifier, which is not necessarily a person.
     *
     * @return The requested immutable context value.
     */
    public String clientId() { return this.clientId; }

    /**
     * Returns an optional host-supplied pseudonymous user identifier.
     *
     * @return The requested immutable context value.
     */
    public String userId() { return this.userId; }

    /**
     * Returns the immutable event context.
     *
     * @return The requested immutable context value.
     */
    public Map<String, Object> parameters() { return this.parameters; }

    /** Builds attribution context using only fields deliberately supplied by the host application. */
    public static final class Builder {
        private String clientId;
        private String userId;
        private boolean identifiedVisitor;
        private final Map<String, Object> parameters = new LinkedHashMap<>();

        /**
         * Sets the non-personal identifier required by the GA Measurement Protocol.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder clientId(String value) { this.clientId = requireText(value); return this; }
        /**
         * Sets an optional pseudonymous user identifier from the host application.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder userId(String value) { this.userId = requireText(value); return this; }
        /**
         * Sets the server name displayed in reports.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder serverName(String value) { return this.put("server_name", value); }
        /**
         * Sets a SHA-256 correlation hash of the transport session identifier for export joins.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder sessionId(String value) { return this.put("mcp_session_id", value == null || value.isBlank() ? value : hash(value)); }
        /**
         * Sets the host conversation identifier for export joins.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder conversationId(String value) { return this.put("conversation_id", value); }
        /**
         * Sets a stable search identifier shared by impressions and downstream outcomes.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder searchId(String value) { return this.put("search_id", value); }
        /**
         * Sets a consented first-party pseudonymous browser identifier for export joins.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder browserId(String value) { return this.put("browser_id", value); }
        /**
         * Sets the product entry point that generated this event.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder entryPoint(String value) { return this.put("entry_point", value); }
        /**
         * Sets a host-resolved agent name when it is already known.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder agentName(String value) { return this.put("agent_name", value); }
        /**
         * Sets an explicitly observed acquisition source or installation tag.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder source(String value) { return this.put("source", value); }
        /**
         * Sets the observed acquisition medium.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder medium(String value) { return this.put("medium", value); }
        /**
         * Sets the observed campaign name.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder campaign(String value) { return this.put("campaign", value); }
        /**
         * Sets the host's acquisition channel classification.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder channel(String value) { return this.put("channel", value); }
        /**
         * Marks an actual identified visitor; it does not create or resolve an identity.
         *
         * @param value The explicitly supplied field value.
         * @return This builder.
         */
        public Builder identifiedVisitor(boolean value) { this.identifiedVisitor = value; return this; }
        /**
         * Creates the immutable context.
         *
         * @return The requested immutable context value.
         */
        public TrackingContext build() {
            if (this.identifiedVisitor && this.clientId == null && this.userId == null) {
                throw new IllegalArgumentException("An identified visitor requires a host-supplied identifier.");
            }
            return new TrackingContext(this);
        }

        private Builder put(String key, String value) {
            if (value != null && !value.isBlank()) {
                int length = value.codePointCount(0, value.length());
                String normalized = length <= 100 ? value : (key.endsWith("_id") ? hash(value) : value.substring(0, value.offsetByCodePoints(0, 100)));
                this.parameters.put(key, normalized);
            }
            return this;
        }

        private static String hash(String value) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
            catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
        }

        private static String requireText(String value) {
            Objects.requireNonNull(value, "Identifier is required.");
            if (value.isBlank()) { throw new IllegalArgumentException("Identifier must not be blank."); }
            return value;
        }
    }
}
