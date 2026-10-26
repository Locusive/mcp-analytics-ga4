package ai.nobi.mcp.ga4.tracking;

import ai.nobi.mcp.ga4.AnalyticsEvent;
import ai.nobi.mcp.ga4.Ga4Client;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Records MCP protocol activity and explicit downstream business facts in a consistent GA4 schema. */
public final class McpAnalytics {
    private static final int TEXT_LIMIT = 100;
    private final Ga4Client client;
    private final UnaryOperator<String> queryRedactor;
    private final McpAgentResolver agentResolver = new McpAgentResolver();

    /**
     * Creates a tracker with query text collection disabled.
     *
     * @param client The configured asynchronous GA4 sender.
     */
    public McpAnalytics(Ga4Client client) { this(client, null); }

    /**
     * Creates a tracker that optionally records query text after the supplied redaction function.
     *
     * @param client The configured asynchronous GA4 sender.
     * @param queryRedactor The opt-in query redactor, or null to omit query text.
     */
    public McpAnalytics(Ga4Client client, UnaryOperator<String> queryRedactor) {
        this.client = Objects.requireNonNull(client);
        this.queryRedactor = queryRedactor;
    }

    /**
     * Records one protocol message; a JSON-RPC notification is explicitly identified.
     *
     * @param context The host-supplied attribution and correlation context.
     * @param request The decoded JSON-RPC request object.
     * @param headers Observed request headers used only for allowlisted attribution.
     * @param outcome The protocol or tool outcome determined by the host.
     * @param durationMillis The nonnegative request duration in milliseconds.
     * @return Whether the event was accepted into the local bounded queue.
     */
    public boolean request(TrackingContext context, Map<String, Object> request, Map<String, String> headers,
                           String outcome, long durationMillis) {
        Objects.requireNonNull(request);
        if (durationMillis < 0) { throw new IllegalArgumentException("Duration must not be negative."); }
        Map<String, Object> values = new LinkedHashMap<>();
        String method = Objects.toString(request.get("method"), "unknown");
        values.put("method", bounded(method));
        Object id = request.get("id");
        boolean validEnvelope = "2.0".equals(request.get("jsonrpc")) && request.get("method") instanceof String
            && (!request.containsKey("id") || id instanceof String || id instanceof Number);
        values.put("protocol_message_count", validEnvelope ? 1 : 0);
        values.put("request_count", validEnvelope && request.containsKey("id") ? 1 : 0);
        values.put("notification_count", validEnvelope && !request.containsKey("id") ? 1 : 0);
        values.put("outcome", bounded(Objects.requireNonNull(outcome)));
        values.put("duration_ms", durationMillis);
        Map<?, ?> parameters = request.get("params") instanceof Map<?, ?> map ? map : Map.of();
        if (validEnvelope && method.equals("tools/call")) {
            values.put("tool_call_count", 1);
            values.put("tool_name", bounded(Objects.toString(parameters.get("name"), "unknown")));
        }
        AgentIdentity agent = this.agentResolver.resolve(context, request, headers);
        values.put("agent_name", bounded(agent.name()));
        values.put("agent_class", agent.classification());
        values.put("agent_evidence", agent.evidence());
        Map<?, ?> metadata = parameters.get("_meta") instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> clientInfo = metadata.get("io.modelcontextprotocol/clientInfo") instanceof Map<?, ?> info ? info
            : parameters.get("clientInfo") instanceof Map<?, ?> info ? info : Map.of();
        if (clientInfo.get("version") instanceof String version) { values.put("agent_version", bounded(version)); }
        Object protocol = metadata.get("io.modelcontextprotocol/protocolVersion");
        if (protocol == null) { protocol = parameters.get("protocolVersion"); }
        if (protocol instanceof String version) { values.put("protocol_version", bounded(version)); }
        this.addObservedSource(context, metadata, headers, values);
        return this.event(context, "mcp_request", values);
    }

    /**
     * Records a query-bearing tool call and its explicit accepted-search and message semantics.
     *
     * @param context The host-supplied attribution and correlation context.
     * @param query The query or page topic, omitted from telemetry unless text collection is enabled.
     * @param kind The search, question, or page classification.
     * @param recordedSearch Whether the host saved an accepted search.
     * @param recordedMessage Whether the host recorded a message for this query.
     * @return Whether the event was accepted into the local bounded queue.
     */
    public boolean search(TrackingContext context, String query, String kind, boolean recordedSearch, boolean recordedMessage) {
        Objects.requireNonNull(query);
        if (query.isBlank()) { throw new IllegalArgumentException("Search text must not be blank."); }
        if (!kind.equals("search") && !kind.equals("question") && !kind.equals("page")) {
            throw new IllegalArgumentException("Search kind must be search, question, or page.");
        }
        if (kind.equals("page") && (recordedSearch || recordedMessage)) {
            throw new IllegalArgumentException("A page topic does not represent an accepted search or message.");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ai_query_count", 1);
        values.put("search_count", recordedSearch ? 1 : 0);
        values.put("message_count", recordedMessage ? 1 : 0);
        values.put("page_request_count", kind.equals("page") ? 1 : 0);
        values.put("search_kind", kind);
        values.put("query_hash", hash(query));
        if (this.queryRedactor != null) {
            String redacted = this.queryRedactor.apply(query);
            if (redacted != null) {
                values.put("query_text", bounded(redacted));
                values.put("query_truncated", redacted.codePointCount(0, redacted.length()) > TEXT_LIMIT ? 1 : 0);
            }
        }
        return this.event(context, kind.equals("page") ? "mcp_page_request" : "mcp_search", values);
    }

    /**
     * Records a downstream link open; the host must durably determine whether it is the search's first click.
     *
     * @param context The host-supplied attribution and correlation context.
     * @param store The result store name.
     * @param linkType The host-classified affiliate, plain, or unknown link type.
     * @param placement The host-classified result placement.
     * @param firstClickForSearch Whether durable host state identifies the first qualifying click for this search.
     * @return Whether the event was accepted into the local bounded queue.
     */
    public boolean linkOpened(TrackingContext context, String store, String linkType, String placement, boolean firstClickForSearch) {
        if (firstClickForSearch && !context.parameters().containsKey("search_id")) {
            throw new IllegalArgumentException("A first search click requires a stable search identifier.");
        }
        return this.event(context, "mcp_link_open", Map.of("click_count", 1, "clicked_search_count", firstClickForSearch ? 1 : 0,
            "store", Objects.requireNonNull(store), "link_type", Objects.requireNonNull(linkType), "placement", Objects.requireNonNull(placement)));
    }

    /**
     * Records an already-attributed purchase; the host must deduplicate transaction identifiers.
     *
     * @param context The host-supplied attribution and correlation context.
     * @param transactionId The already-deduplicated transaction identifier.
     * @param value The explicitly supplied field value.
     * @param currency The uppercase three-letter currency code.
     * @param itemCount The attributed nonnegative item quantity.
     * @return Whether the event was accepted into the local bounded queue.
     */
    public boolean purchase(TrackingContext context, String transactionId, BigDecimal value, String currency, int itemCount) {
        if (value.signum() < 0 || itemCount < 0 || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Purchase needs nonnegative value/items and an uppercase three-letter currency.");
        }
        return this.event(context, "mcp_purchase", Map.of("transaction_id", Objects.requireNonNull(transactionId),
            "order_count", 1, "value", value, "currency", currency, "item_count", itemCount));
    }

    /**
     * Records a generic business fact, preserving context and avoiding accidental overwrites of correlation fields.
     *
     * @param context The host-supplied attribution and correlation context.
     * @param eventName The GA4 event name.
     * @param details The explicitly selected scalar business fields.
     * @return Whether the event was accepted into the local bounded queue.
     */
    public boolean event(TrackingContext context, String eventName, Map<String, Object> details) {
        Objects.requireNonNull(context);
        Map<String, Object> values = new LinkedHashMap<>(context.parameters());
        for (Map.Entry<String, Object> entry : details.entrySet()) {
            if (values.containsKey(entry.getKey()) && !entry.getKey().equals("agent_name")) {
                throw new IllegalArgumentException("An event must not overwrite context field: " + entry.getKey());
            }
            values.put(entry.getKey(), entry.getValue());
        }
        return this.client.track(new AnalyticsEvent(eventName, context.clientId(), Instant.now(), values, context.userId()));
    }

    private void addObservedSource(TrackingContext context, Map<?, ?> metadata, Map<String, String> headers, Map<String, Object> values) {
        if (context.parameters().containsKey("source")) { return; }
        Object supplied = metadata.get("source");
        if (!(supplied instanceof String source) || source.isBlank()) { supplied = metadata.get("utm_source"); }
        if (supplied instanceof String tag && !tag.isBlank()) {
            values.put("source", bounded(tag));
            values.put("source_evidence", "client_tag");
            return;
        }
        String referer = headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase("referer"))
            .map(Map.Entry::getValue).filter(Objects::nonNull).findFirst().orElse("");
        try {
            String host = URI.create(referer).getHost();
            if (host != null) { values.put("source", bounded(host)); values.put("source_evidence", "referrer_host"); return; }
        } catch (IllegalArgumentException exception) { /* Invalid referrers do not establish an acquisition source. */ }
        values.put("source", "unknown");
    }

    private static String bounded(String value) {
        int length = value.codePointCount(0, value.length());
        return length <= TEXT_LIMIT ? value : value.substring(0, value.offsetByCodePoints(0, TEXT_LIMIT));
    }

    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable.", exception); }
    }
}
