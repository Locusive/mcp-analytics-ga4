package ai.nobi.mcp.ga4.tracking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.nobi.mcp.ga4.Ga4Client;
import ai.nobi.mcp.ga4.Ga4Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the public tracking hooks by independently observing real GA4 HTTP payloads. */
class McpAnalyticsIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> envelopes = new CopyOnWriteArrayList<>();
    private HttpServer collector;
    private Ga4Client client;
    private McpAnalytics analytics;

    @BeforeEach
    void startCollector() throws IOException {
        this.collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.collector.createContext("/mp/collect", exchange -> {
            this.envelopes.add(this.mapper.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        this.collector.start();
        this.client = new Ga4Client(Ga4Config.builder().measurementId("G-TEST123").apiSecret("fixture-secret")
                .endpoint(URI.create("http://127.0.0.1:" + this.collector.getAddress().getPort() + "/mp/collect"))
                .requestTimeout(Duration.ofSeconds(2)).build());
        this.analytics = new McpAnalytics(this.client);
    }

    @AfterEach
    void closeResources() {
        this.client.close();
        this.collector.stop(0);
    }

    @Test
    void countsRequestsToolsErrorsAndNotificationsSeparately() {
        TrackingContext context = context();
        this.analytics.request(context, request(1, "initialize", Map.of("protocolVersion", "2025-11-25",
                "clientInfo", Map.of("name", "Claude Code", "version", "1.0"))), Map.of(), "success", 3);
        this.analytics.request(context, request(2, "ping", Map.of()), Map.of(), "success", 1);
        this.analytics.request(context, request(3, "tools/list", Map.of()), Map.of(), "success", 2);
        this.analytics.request(context, request(4, "tools/call", Map.of("name", "search")), Map.of(), "success", 20);
        this.analytics.request(context, Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), Map.of(), "success", 0);
        this.analytics.request(context, request(5, "tools/call", Map.of("name", "search")), Map.of(), "tool_error", 5);

        List<JsonNode> events = this.events();
        assertEquals(6, events.size());
        assertEquals(6, sum(events, "protocol_message_count"));
        assertEquals(5, sum(events, "request_count"));
        assertEquals(1, sum(events, "notification_count"));
        assertEquals(2, sum(events, "tool_call_count"));
        assertEquals("Claude Code", params(events.get(0)).path("agent_name").asText());
        assertEquals("2025-11-25", params(events.get(0)).path("protocol_version").asText());
        assertEquals("1.0", params(events.get(0)).path("agent_version").asText());
        assertEquals("tool_error", params(events.get(5)).path("outcome").asText());
        assertEquals(20, params(events.get(3)).path("duration_ms").asLong());
        assertFalse(params(events.get(4)).has("tool_name"));
        assertTrue(events.stream().allMatch(event -> event.path("name").asText().equals("mcp_request")));
    }

    @Test
    void resolvesModernLegacyHostAndProviderClientEvidence() {
        this.analytics.request(context(), request(1, "tools/call", Map.of("name", "search", "_meta", Map.of(
                "io.modelcontextprotocol/clientInfo", Map.of("name", "openai-mcp", "version", "1.2.3"),
                "io.modelcontextprotocol/protocolVersion", "2026-07-28"))), Map.of(), "success", 1);
        this.analytics.request(context(), request(2, "ping", Map.of("_meta", Map.of("clientInfo", Map.of("name", "Claude")))),
                Map.of(), "success", 1);
        this.analytics.request(TrackingContext.builder().agentName("Claude").build(), request(3, "ping", Map.of()),
                Map.of("X-Anthropic-Client", "Cowork"), "success", 1);
        this.analytics.request(TrackingContext.builder().agentName("Claude").build(), request(4, "ping", Map.of()),
                Map.of("USER-AGENT", "research-probe/1.0"), "success", 1);
        this.analytics.request(TrackingContext.builder().agentName("Cursor").build(), request(5, "initialize",
                Map.of("clientInfo", Map.of("name", "Claude"))), Map.of(), "success", 1);
        this.analytics.request(context(), request(6, "ping", Map.of()), Map.of("User-Agent", "openai-mcp/1.0"), "success", 1);
        this.analytics.request(context(), request(7, "ping", Map.of()), Map.of("User-Agent", "unrecognized-http/1.0"), "success", 1);
        this.analytics.request(context(), request(8, "ping", Map.of()), Map.of("User-Agent", "sunrise-probe/1.0"), "success", 1);
        this.analytics.request(context(), request(9, "ping", Map.of()), Map.of("User-Agent", "Google-HealthCheck/1.0"), "success", 1);

        List<JsonNode> events = this.events();
        assertEquals(List.of("ChatGPT", "Claude", "Cowork", "research-probe", "Cursor", "ChatGPT", "Unknown", "sunrise-probe", "Google-HealthCheck"),
                events.stream().map(event -> params(event).path("agent_name").asText()).toList());
        assertEquals("client_info", params(events.get(0)).path("agent_evidence").asText());
        assertEquals("2026-07-28", params(events.get(0)).path("protocol_version").asText());
        assertEquals("1.2.3", params(events.get(0)).path("agent_version").asText());
        assertEquals("provider_header", params(events.get(2)).path("agent_evidence").asText());
        assertEquals("bot", params(events.get(3)).path("agent_class").asText());
        assertEquals("host", params(events.get(4)).path("agent_evidence").asText());
        assertEquals("bot", params(events.get(7)).path("agent_class").asText());
        assertEquals("health_check", params(events.get(8)).path("agent_class").asText());
        assertFalse(this.envelopes.toString().contains("unrecognized-http"));
    }

    @Test
    void preservesDiscoveryTagsAndLimitsReferrerFallbackToTheHost() {
        this.analytics.request(TrackingContext.builder().source("github").medium("installation").campaign("launch").build(),
                request(1, "ping", Map.of("_meta", Map.of("source", "spoofed"))), Map.of(), "success", 0);
        this.analytics.request(context(), request(2, "ping", Map.of("_meta", Map.of("utm_source", "mcp-directory"))),
                Map.of("Referer", "https://other.example/private?token=fixture-private"), "success", 0);
        this.analytics.request(context(), request(3, "ping", Map.of()),
                Map.of("REFERER", "https://catalog.example/private?token=fixture-private"), "success", 0);
        this.analytics.request(context(), request(4, "ping", Map.of()), Map.of("Referer", "bad referrer"), "success", 0);
        this.analytics.request(context(), request(5, "ping", Map.of("_meta", Map.of("source", " ", "utm_source", "tag-from-utm"))),
                Map.of("Referer", "https://other.example/private"), "success", 0);

        List<JsonNode> events = this.events();
        assertEquals(List.of("github", "mcp-directory", "catalog.example", "unknown", "tag-from-utm"),
                events.stream().map(event -> params(event).path("source").asText()).toList());
        assertEquals("installation", params(events.get(0)).path("medium").asText());
        assertEquals("launch", params(events.get(0)).path("campaign").asText());
        assertEquals("client_tag", params(events.get(1)).path("source_evidence").asText());
        assertEquals("referrer_host", params(events.get(2)).path("source_evidence").asText());
        assertEquals("client_tag", params(events.get(4)).path("source_evidence").asText());
        assertFalse(this.envelopes.toString().contains("fixture-private"));
        assertFalse(this.envelopes.toString().contains("/private"));
    }

    @Test
    void excludesRawQueriesArgumentsAndHeadersByDefault() {
        String privateQuery = "Email person@example.test and use fixture-private-token";
        this.analytics.request(context(), request(1, "tools/call", Map.of("name", "search", "arguments",
                Map.of("query", privateQuery))), Map.of("Authorization", "Bearer fixture-private-token"), "success", 3);
        this.analytics.search(context(), privateQuery, "search", true, false);
        this.analytics.search(context(), "hello", "search", true, false);

        List<JsonNode> events = this.events();
        assertFalse(this.envelopes.toString().contains("person@example.test"));
        assertFalse(this.envelopes.toString().contains("fixture-private-token"));
        assertFalse(params(events.get(1)).has("query_text"));
        assertEquals(64, params(events.get(1)).path("query_hash").asText().length());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                params(events.get(2)).path("query_hash").asText());
    }

    @Test
    void redactsBeforeUnicodeSafeTruncationAndSupportsDroppingText() {
        String approved = "😀".repeat(99) + "éZ";
        McpAnalytics redacted = new McpAnalytics(this.client, query -> approved);
        redacted.search(context(), "private email", "search", true, false);
        McpAnalytics suppressed = new McpAnalytics(this.client, query -> null);
        suppressed.search(context(), "private email", "search", true, false);

        List<JsonNode> events = this.events();
        String text = params(events.get(0)).path("query_text").asText();
        assertEquals("😀".repeat(99) + "é", text);
        assertEquals(100, text.codePointCount(0, text.length()));
        assertEquals(1, params(events.get(0)).path("query_truncated").asInt());
        assertFalse(params(events.get(1)).has("query_text"));
        assertFalse(this.envelopes.toString().contains("private email"));
    }

    @Test
    void separatesAiQueriesRecordedSearchesMessagesAndPageTopics() {
        this.analytics.search(context(), "boots", "search", true, false);
        this.analytics.search(context(), "Are these available?", "question", true, true);
        this.analytics.search(context(), "leather boots", "page", false, false);
        this.analytics.event(context(), "mcp_message", Map.of("message_count", 1));

        List<JsonNode> events = this.events();
        assertEquals(List.of("mcp_search", "mcp_search", "mcp_page_request", "mcp_message"),
                events.stream().map(event -> event.path("name").asText()).toList());
        assertEquals(3, sum(events, "ai_query_count"));
        assertEquals(2, sum(events, "search_count"));
        assertEquals(2, sum(events, "message_count"));
        assertEquals(1, sum(events, "page_request_count"));
        assertEquals(0, params(events.get(2)).path("search_count").asInt());
        assertEquals(0, params(events.get(2)).path("message_count").asInt());
        assertThrows(IllegalArgumentException.class, () -> this.analytics.search(context(), "topic", "page", true, false));
        assertThrows(IllegalArgumentException.class, () -> this.analytics.search(context(), "topic", "page", false, true));
        assertThrows(IllegalArgumentException.class, () -> this.analytics.search(context(), " ", "search", true, false));
        assertThrows(IllegalArgumentException.class, () -> this.analytics.search(context(), "topic", "invalid", true, false));
    }

    @Test
    void countsEveryObservedClickButOnlyTheHostsFirstClickForEachSearch() {
        TrackingContext context = TrackingContext.builder().clientId("anonymous-123").searchId("search-123")
                .conversationId("conversation-123").browserId("browser-123").build();
        this.analytics.linkOpened(context, "Example Store", "plain", "organic", true);
        this.analytics.linkOpened(context, "Example Store", "plain", "organic", false);

        List<JsonNode> events = this.events();
        assertEquals(2, sum(events, "click_count"));
        assertEquals(1, sum(events, "clicked_search_count"));
        assertTrue(events.stream().allMatch(event -> params(event).path("search_id").asText().equals("search-123")));
        assertEquals("browser-123", params(events.get(0)).path("browser_id").asText());
        assertEquals("conversation-123", params(events.get(0)).path("conversation_id").asText());
        assertThrows(IllegalArgumentException.class,
                () -> this.analytics.linkOpened(context(), "Store", "plain", "organic", true));
    }

    @Test
    void sendsAttributedPurchaseValuesWithTheOriginalHostContext() {
        TrackingContext context = TrackingContext.builder().clientId("client-123").userId("user-123")
                .serverName("catalog").searchId("search-123").agentName("Claude").source("github")
                .identifiedVisitor(true).build();
        assertTrue(this.analytics.purchase(context, "order-123", new BigDecimal("24.50"), "USD", 2));

        List<JsonNode> events = this.events();
        assertEquals(1, events.size());
        assertEquals("mcp_purchase", events.get(0).path("name").asText());
        JsonNode parameters = params(events.get(0));
        assertTrue(parameters.path("value").isNumber());
        assertEquals(24.5, parameters.path("value").asDouble());
        assertEquals("USD", parameters.path("currency").asText());
        assertEquals(2, parameters.path("item_count").asInt());
        assertEquals(1, parameters.path("order_count").asInt());
        assertEquals("order-123", parameters.path("transaction_id").asText());
        assertEquals("search-123", parameters.path("search_id").asText());
        assertEquals("Claude", parameters.path("agent_name").asText());
        assertEquals("identified", parameters.path("identity_kind").asText());
        assertEquals("client-123", this.envelopes.get(0).path("client_id").asText());
        assertEquals("user-123", this.envelopes.get(0).path("user_id").asText());
        assertFalse(parameters.has("items"));
        assertThrows(IllegalArgumentException.class, () -> this.analytics.purchase(context, "bad", new BigDecimal("-1"), "USD", 1));
        assertThrows(IllegalArgumentException.class, () -> this.analytics.purchase(context, "bad", BigDecimal.ONE, "usd", 1));
        assertThrows(IllegalArgumentException.class, () -> this.analytics.purchase(context, "bad", BigDecimal.ONE, "USD", -1));
    }

    @Test
    void keepsAnonymousFallbackStableAndProtectsHostCorrelationFields() {
        TrackingContext first = context();
        TrackingContext second = context();
        assertEquals(first.clientId(), second.clientId());
        assertEquals("unidentified", first.parameters().get("identity_kind"));
        assertThrows(IllegalArgumentException.class, () -> TrackingContext.builder().identifiedVisitor(true).build());
        assertThrows(UnsupportedOperationException.class, () -> first.parameters().put("source", "other"));
        TrackingContext identified = TrackingContext.builder().clientId("opaque-client").searchId("stable-search")
                .source("github").build();
        assertThrows(IllegalArgumentException.class,
                () -> this.analytics.event(identified, "mcp_cart", Map.of("search_id", "replacement")));
        assertThrows(IllegalArgumentException.class,
                () -> this.analytics.request(identified, request(1, "ping", Map.of()), Map.of(), "success", -1));
        assertEquals(0, this.client.stats().accepted());
    }

    @Test
    void keepsTheRichestRequestContextWithinGaParameterLimits() throws Exception {
        TrackingContext context = TrackingContext.builder().clientId("client-123").userId("user-123")
                .serverName("catalog").sessionId("mcp-session-123").conversationId("conversation-123")
                .searchId("search-123").browserId("browser-123").entryPoint("mcp")
                .agentName("Claude Code").medium("installation").campaign("launch").channel("Organic AI")
                .identifiedVisitor(true).build();
        assertTrue(this.analytics.request(context, request(1, "tools/call", Map.of("name", "search", "_meta", Map.of(
                "io.modelcontextprotocol/protocolVersion", "2026-07-28", "source", "directory",
                "io.modelcontextprotocol/clientInfo", Map.of("name", "Claude Code", "version", "1.2.3")))), Map.of(), "success", 12));
        assertTrue(this.analytics.request(context, request(2, "tools/call", Map.of("name", "search", "_meta", Map.of(
                "io.modelcontextprotocol/protocolVersion", "2026-07-28",
                "io.modelcontextprotocol/clientInfo", Map.of("name", "Claude Code", "version", "1.2.3")))),
                Map.of("Referer", "https://directory.example/install?secret=private"), "success", 12));

        List<JsonNode> events = this.events();
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(event -> params(event).size() <= 25));
        assertEquals(25, params(events.get(0)).size());
        assertEquals("1.2.3", params(events.get(0)).path("agent_version").asText());
        assertEquals("directory", params(events.get(0)).path("source").asText());
        assertEquals("client_tag", params(events.get(0)).path("source_evidence").asText());
        assertEquals("directory.example", params(events.get(1)).path("source").asText());
        assertEquals("referrer_host", params(events.get(1)).path("source_evidence").asText());
        String sessionHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("mcp-session-123".getBytes(StandardCharsets.UTF_8)));
        assertEquals(sessionHash, params(events.get(0)).path("mcp_session_id").asText());
        assertFalse(this.envelopes.toString().contains("mcp-session-123"));
        assertEquals("search-123", params(events.get(0)).path("search_id").asText());
        assertEquals("identified", params(events.get(0)).path("identity_kind").asText());
        assertEquals("user-123", this.envelopes.get(0).path("user_id").asText());
    }

    @Test
    void boundsDisplayMetadataAndHashesLongCorrelationIdsWithoutDroppingRequests() throws Exception {
        String longName = "Custom-" + "😀".repeat(120);
        String sessionToken = "fixture-session-token-" + "x".repeat(180);
        String conversationId = "conversation-" + "y".repeat(180);
        TrackingContext context = TrackingContext.builder().agentName(longName).sessionId(sessionToken)
                .conversationId(conversationId).searchId("search-" + "z".repeat(180))
                .browserId("browser-" + "b".repeat(180)).serverName("server-" + "s".repeat(180)).build();
        assertTrue(this.analytics.request(context, request(1, "tools/call", Map.of("name", "search")), Map.of(), "success", 1));
        assertTrue(this.analytics.search(context, "boots", "search", true, false));

        List<JsonNode> events = this.events();
        assertEquals(1, sum(events, "request_count"));
        assertEquals(1, sum(events, "search_count"));
        JsonNode parameters = params(events.get(0));
        String agentName = parameters.path("agent_name").asText();
        assertEquals(100, agentName.codePointCount(0, agentName.length()));
        assertEquals(100, parameters.path("server_name").asText().length());
        String expectedSession = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sessionToken.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedSession, parameters.path("mcp_session_id").asText());
        assertTrue(parameters.path("conversation_id").asText().matches("[a-f0-9]{64}"));
        assertTrue(parameters.path("search_id").asText().matches("[a-f0-9]{64}"));
        assertTrue(parameters.path("browser_id").asText().matches("[a-f0-9]{64}"));
        assertFalse(this.envelopes.toString().contains(sessionToken));
        assertFalse(this.envelopes.toString().contains(conversationId));
    }

    /** Flushes the real sender and flattens the independently captured HTTP envelopes. */
    private List<JsonNode> events() {
        assertTrue(this.client.flush(Duration.ofSeconds(3)));
        assertEquals(0, this.client.stats().failed());
        assertEquals(0, this.client.stats().rejected());
        List<JsonNode> events = new ArrayList<>();
        this.envelopes.forEach(envelope -> envelope.path("events").forEach(events::add));
        return events;
    }

    /** Returns an event's wire parameters. */
    private static JsonNode params(JsonNode event) {
        return event.path("params");
    }

    /** Sums a public metric across independently captured event records. */
    private static long sum(List<JsonNode> events, String metric) {
        return events.stream().mapToLong(event -> params(event).path(metric).asLong()).sum();
    }

    /** Creates a deliberately anonymous context without inferred identity. */
    private static TrackingContext context() {
        return TrackingContext.builder().serverName("test-catalog").build();
    }

    /** Creates a valid protocol request with explicit caller-supplied parameters. */
    private static Map<String, Object> request(int id, String method, Map<String, Object> parameters) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", parameters);
    }
}
