package ai.nobi.mcp.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.nobi.mcp.ga4.Ga4Client;
import ai.nobi.mcp.ga4.Ga4Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the runnable sample through actual MCP and outcome HTTP requests. */
class SampleMcpServerIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final List<JsonNode> observed = new ArrayList<>();
    private HttpServer collector;
    private Ga4Client analyticsClient;
    private SampleMcpServer sample;

    @BeforeEach
    void startServers() throws IOException {
        this.collector = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.collector.createContext("/mp/collect", exchange -> {
            JsonNode envelope = this.mapper.readTree(exchange.getRequestBody());
            envelope.path("events").forEach(this.received::add);
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        this.collector.start();
        this.analyticsClient = new Ga4Client(Ga4Config.builder().measurementId("G-TEST123").apiSecret("fixture-secret")
                .endpoint(URI.create("http://127.0.0.1:" + this.collector.getAddress().getPort() + "/mp/collect"))
                .requestTimeout(Duration.ofSeconds(2)).build());
        this.sample = new SampleMcpServer(0, this.analyticsClient);
    }

    @AfterEach
    void closeServers() {
        this.sample.close();
        this.collector.stop(0);
    }

    @Test
    void initializesPingsAndListsToolsUsingTheAdvertisedProtocol() throws Exception {
        HttpResponse<String> initialize = this.rpc(1, "initialize", Map.of("protocolVersion", "2025-11-25",
                "clientInfo", Map.of("name", "Claude Code", "version", "1.0")));
        assertEquals(200, initialize.statusCode());
        JsonNode initialization = this.json(initialize).path("result");
        assertEquals("2025-11-25", initialization.path("protocolVersion").asText());
        assertTrue(initialization.path("capabilities").has("tools"));
        assertEquals("mcp-ga4-example", initialization.path("serverInfo").path("name").asText());
        HttpResponse<String> ping = this.rpc(2, "ping", Map.of());
        assertEquals(200, ping.statusCode());
        assertEquals(0, this.json(ping).path("result").size());
        HttpResponse<String> list = this.rpc(3, "tools/list", Map.of());
        JsonNode tools = this.json(list).path("result").path("tools");
        assertEquals(2, tools.size());
        assertEquals("search", tools.get(0).path("name").asText());
        assertEquals("query", tools.get(0).path("inputSchema").path("required").get(0).asText());
        assertEquals("find_page", tools.get(1).path("name").asText());

        List<JsonNode> events = this.awaitEvents(3);
        assertEquals(3, sum(events, "request_count"));
        assertEquals(3, sum(events, "protocol_message_count"));
        assertEquals(0, sum(events, "tool_call_count"));
        assertEquals("Claude Code", params(events.get(0)).path("agent_name").asText());
        assertEquals("2025-11-25", params(events.get(0)).path("protocol_version").asText());
    }

    @Test
    void separatesSearchPageToolErrorsAndProtocolErrors() throws Exception {
        HttpResponse<String> search = this.rpc(1, "tools/call", Map.of("name", "search", "arguments", Map.of("query", "leather boots")));
        JsonNode searchResult = this.json(search).path("result");
        assertFalse(searchResult.path("isError").asBoolean());
        assertFalse(searchResult.path("structuredContent").path("search_id").asText().isBlank());
        assertTrue(searchResult.path("structuredContent").path("url").asText().startsWith(this.sample.baseUri() + "/click?"));
        HttpResponse<String> page = this.rpc(2, "tools/call", Map.of("name", "find_page", "arguments", Map.of("topic", "coffee gifts")));
        assertFalse(this.json(page).path("result").path("isError").asBoolean());
        HttpResponse<String> toolError = this.rpc(3, "tools/call", Map.of("name", "search", "arguments", Map.of("query", " ")));
        assertEquals(200, toolError.statusCode());
        assertTrue(this.json(toolError).path("result").path("isError").asBoolean());
        HttpResponse<String> unknownTool = this.rpc(4, "tools/call", Map.of("name", "missing"));
        assertEquals(-32602, this.json(unknownTool).path("error").path("code").asInt());
        HttpResponse<String> unknownMethod = this.rpc(5, "unknown/method", Map.of());
        assertEquals(-32601, this.json(unknownMethod).path("error").path("code").asInt());

        List<JsonNode> events = this.awaitEvents(8);
        assertEquals(5, sum(events, "request_count"));
        assertEquals(4, sum(events, "tool_call_count"));
        assertEquals(2, sum(events, "ai_query_count"));
        assertEquals(1, sum(events, "search_count"));
        assertEquals(1, sum(events, "page_request_count"));
        assertEquals(0, sum(events, "message_count"));
        assertEquals(1, events.stream().filter(event -> event.path("name").asText().equals("mcp_impression")).count());
        assertEquals(List.of("success", "success", "tool_error", "protocol_error", "protocol_error"),
                named(events, "mcp_request").stream().map(event -> params(event).path("outcome").asText()).toList());
        assertFalse(events.toString().contains("leather boots"));
        assertFalse(events.toString().contains("coffee gifts"));
    }

    @Test
    void handlesMalformedBodiesNullIdsAndNotificationsWithoutInventingRequests() throws Exception {
        HttpResponse<String> parseError = this.send("POST", "/mcp", "{", Map.of());
        assertEquals(400, parseError.statusCode());
        assertEquals(-32700, this.json(parseError).path("error").path("code").asInt());
        assertTrue(this.json(parseError).path("id").isNull());
        HttpResponse<String> nullBody = this.send("POST", "/mcp", "null", Map.of());
        assertEquals(400, nullBody.statusCode());
        assertEquals(-32600, this.json(nullBody).path("error").path("code").asInt());
        HttpResponse<String> nullId = this.send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":null}", Map.of());
        assertEquals(400, nullId.statusCode());
        assertTrue(this.json(nullId).path("id").isNull());
        HttpResponse<String> objectId = this.send("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"id\":{}}", Map.of());
        assertEquals(400, objectId.statusCode());
        assertTrue(this.json(objectId).path("id").isNull());
        HttpResponse<String> notification = this.send("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of());
        assertEquals(202, notification.statusCode());
        assertEquals("", notification.body());
        HttpResponse<String> get = this.send("GET", "/mcp", null, Map.of());
        assertEquals(405, get.statusCode());
        assertEquals("POST", get.headers().firstValue("Allow").orElseThrow());

        List<JsonNode> events = this.awaitEvents(5);
        assertEquals(0, sum(events, "request_count"));
        assertEquals(1, sum(events, "protocol_message_count"));
        assertEquals(1, sum(events, "notification_count"));
        assertEquals(0, sum(events, "tool_call_count"));
        assertEquals(List.of("parse_error", "invalid_request", "invalid_request", "invalid_request", "success"),
                events.stream().map(event -> params(event).path("outcome").asText()).toList());
    }

    @Test
    void countsRepeatClicksButOnlyOneClickedSearchAndPreservesTheBrowserCookie() throws Exception {
        String searchId = this.createSearch();
        HttpResponse<String> first = this.send("GET", "/click?search_id=" + searchId, null, Map.of());
        assertEquals(200, first.statusCode());
        String cookie = first.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertTrue(cookie.startsWith("sample_browser_id="));
        HttpResponse<String> repeat = this.send("GET", "/click?search_id=" + searchId, null, Map.of("Cookie", cookie));
        assertEquals(200, repeat.statusCode());
        assertTrue(repeat.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(404, this.send("GET", "/click?search_id=unknown", null, Map.of()).statusCode());

        List<JsonNode> events = this.awaitEvents(5);
        List<JsonNode> clicks = named(events, "mcp_link_open");
        assertEquals(2, clicks.size());
        assertEquals(2, sum(clicks, "click_count"));
        assertEquals(1, sum(clicks, "clicked_search_count"));
        assertEquals(params(clicks.get(0)).path("browser_id"), params(clicks.get(1)).path("browser_id"));
        assertEquals(cookie.substring("sample_browser_id=".length()), params(clicks.get(0)).path("browser_id").asText());
        assertTrue(clicks.stream().allMatch(event -> params(event).path("search_id").asText().equals(searchId)));
        assertEquals(1, sum(events, "request_count"));
    }

    @Test
    void deduplicatesSampleTransactionsAndEmitsTypedAttributedOutcomes() throws Exception {
        String searchId = this.createSearch();
        assertEquals(400, this.send("POST", "/demo/outcomes?search_id=" + searchId, "", Map.of()).statusCode());
        String path = "/demo/outcomes?search_id=" + searchId + "&transaction_id=order-123";
        HttpResponse<String> first = this.send("POST", path, "", Map.of());
        assertEquals(200, first.statusCode());
        assertTrue(this.json(first).path("recorded").asBoolean());
        HttpResponse<String> duplicate = this.send("POST", path, "", Map.of());
        assertEquals(200, duplicate.statusCode());
        assertTrue(this.json(duplicate).path("duplicate").asBoolean());

        List<JsonNode> events = this.awaitEvents(7);
        assertEquals(1, sum(events, "cart_count"));
        assertEquals(1, sum(events, "product_view_count"));
        assertEquals(1, sum(events, "query_pageview_count"));
        assertEquals(1, sum(events, "order_count"));
        List<JsonNode> purchases = named(events, "mcp_purchase");
        assertEquals(1, purchases.size());
        JsonNode purchase = params(purchases.get(0));
        assertEquals("order-123", purchase.path("transaction_id").asText());
        assertTrue(purchase.path("value").isNumber());
        assertEquals(24.5, purchase.path("value").asDouble());
        assertEquals("USD", purchase.path("currency").asText());
        assertEquals(2, purchase.path("item_count").asInt());
        assertEquals(searchId, purchase.path("search_id").asText());
        assertEquals("unidentified", purchase.path("identity_kind").asText());
        assertEquals("Claude Code", purchase.path("agent_name").asText());
        assertTrue(events.stream().allMatch(event -> params(event).path("agent_name").asText().equals("Claude Code")));
        assertEquals(1, sum(events, "request_count"));
    }

    @Test
    void rejectsUntrustedOriginAndHostBeforeDispatchAndAcceptsTheLocalOrigin() throws Exception {
        String ping = this.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"));
        assertEquals(403, this.send("POST", "/mcp", ping, Map.of("Origin", "https://attacker.example")).statusCode());
        assertEquals(403, this.send("POST", "/mcp", ping, Map.of("Origin", "null")).statusCode());

        try (Socket socket = new Socket("127.0.0.1", this.sample.baseUri().getPort())) {
            socket.setSoTimeout(3_000);
            byte[] body = ping.getBytes(StandardCharsets.UTF_8);
            String headers = "POST /mcp HTTP/1.1\r\nHost: attacker.example\r\nContent-Type: application/json\r\nContent-Length: "
                    + body.length + "\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 403"));
        }

        assertEquals(200, this.send("POST", "/mcp", ping, Map.of("Origin", this.sample.baseUri().toString())).statusCode());
        List<JsonNode> events = this.awaitEvents(1);
        assertEquals(1, sum(events, "request_count"));
        assertEquals("ping", params(events.get(0)).path("method").asText());
        assertEquals("success", params(events.get(0)).path("outcome").asText());
    }

    @Test
    void pageTopicClicksDoNotBecomeClickedAcceptedSearches() throws Exception {
        HttpResponse<String> page = this.rpc(1, "tools/call", Map.of("name", "find_page", "arguments", Map.of("topic", "boots"),
                "_meta", Map.of("io.modelcontextprotocol/clientInfo", Map.of("name", "Cowork", "version", "1.0"))));
        String searchId = this.json(page).path("result").path("structuredContent").path("search_id").asText();
        assertEquals(200, this.send("GET", "/click?search_id=" + searchId, null, Map.of()).statusCode());

        List<JsonNode> events = this.awaitEvents(3);
        assertEquals(1, sum(events, "page_request_count"));
        assertEquals(0, sum(events, "search_count"));
        assertEquals(1, sum(events, "click_count"));
        assertEquals(0, sum(events, "clicked_search_count"));
        assertTrue(events.stream().allMatch(event -> params(event).path("agent_name").asText().equals("Cowork")));
    }

    @Test
    void longReportedNamesAndSessionTokensDoNotFailToolsOrExposeTheToken() throws Exception {
        String agent = "Custom-Agent-" + "😀".repeat(120);
        String session = "fixture-sensitive-session-" + "x".repeat(180);
        String body = this.mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "search", "arguments", Map.of("query", "boots"), "_meta",
                        Map.of("io.modelcontextprotocol/clientInfo", Map.of("name", agent, "version", "1.0")))));
        HttpResponse<String> response = this.send("POST", "/mcp", body, Map.of("Mcp-Session-Id", session));
        assertEquals(200, response.statusCode());
        assertFalse(this.json(response).path("result").path("isError").asBoolean());

        List<JsonNode> events = this.awaitEvents(3);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(session.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, sum(events, "search_count"));
        assertEquals(1, sum(events, "request_count"));
        assertFalse(events.toString().contains(session));

        for (JsonNode event : events) {
            JsonNode parameters = params(event);
            String reported = parameters.path("agent_name").asText();
            assertEquals(100, reported.codePointCount(0, reported.length()));
            assertEquals(expectedHash, parameters.path("mcp_session_id").asText());
        }
    }

    @Test
    void rejectsOversizedBodiesWithoutSendingTheirContentsToGa() throws Exception {
        String body = "{\"secret\":\"" + "private".repeat(10_000) + "\"}";
        HttpResponse<String> response = this.send("POST", "/mcp", body, Map.of());
        assertEquals(413, response.statusCode());
        assertEquals(-32600, this.json(response).path("error").path("code").asInt());

        List<JsonNode> events = this.awaitEvents(1);
        assertEquals("invalid_request", params(events.get(0)).path("outcome").asText());
        assertEquals(0, sum(events, "request_count"));
        assertEquals(0, sum(events, "notification_count"));
        assertFalse(events.toString().contains("private"));
    }

    /** Creates one accepted search through the real protocol endpoint. */
    private String createSearch() throws Exception {
        HttpResponse<String> response = this.rpc(1, "tools/call", Map.of("name", "search", "arguments", Map.of("query", "coffee gifts"),
                "_meta", Map.of("io.modelcontextprotocol/clientInfo", Map.of("name", "Claude Code", "version", "1.0"))));
        assertEquals(200, response.statusCode());
        return this.json(response).path("result").path("structuredContent").path("search_id").asText();
    }

    /** Sends one ordinary JSON-RPC request to the running sample. */
    private HttpResponse<String> rpc(int id, String method, Map<String, Object> parameters) throws Exception {
        return this.send("POST", "/mcp", this.mapper.writeValueAsString(
                Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", parameters)), Map.of());
    }

    /** Performs an actual bounded HTTP exchange without bypassing the sample's route handlers. */
    private HttpResponse<String> send(String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(this.sample.baseUri().resolve(path)).timeout(Duration.ofSeconds(3));
        headers.forEach(request::header);

        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        return this.http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Parses a sample response independently of the sample's implementation. */
    private JsonNode json(HttpResponse<String> response) throws IOException {
        return this.mapper.readTree(response.body());
    }

    /** Waits for observed delivery, including telemetry emitted after an HTTP response is closed. */
    private List<JsonNode> awaitEvents(int expected) throws InterruptedException {
        while (this.observed.size() < expected) {
            JsonNode event = this.received.poll(3, TimeUnit.SECONDS);
            assertNotNull(event, "The GA collector did not receive the expected sample event.");
            this.observed.add(event);
        }

        assertTrue(this.analyticsClient.flush(Duration.ofSeconds(3)));
        this.received.drainTo(this.observed);
        assertEquals(expected, this.observed.size());
        assertEquals(0, this.analyticsClient.stats().failed());
        assertEquals(0, this.analyticsClient.stats().rejected());
        return List.copyOf(this.observed);
    }

    /** Selects a public GA event name from captured output. */
    private static List<JsonNode> named(List<JsonNode> events, String name) {
        return events.stream().filter(event -> event.path("name").asText().equals(name)).toList();
    }

    /** Returns an event's public GA parameters. */
    private static JsonNode params(JsonNode event) {
        return event.path("params");
    }

    /** Sums a public metric from captured event payloads. */
    private static long sum(List<JsonNode> events, String metric) {
        return events.stream().mapToLong(event -> params(event).path(metric).asLong()).sum();
    }
}
