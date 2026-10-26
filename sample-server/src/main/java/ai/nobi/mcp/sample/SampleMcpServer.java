package ai.nobi.mcp.sample;

import ai.nobi.mcp.ga4.Ga4Client;
import ai.nobi.mcp.ga4.Ga4Config;
import ai.nobi.mcp.ga4.tracking.McpAnalytics;
import ai.nobi.mcp.ga4.tracking.McpAgentResolver;
import ai.nobi.mcp.ga4.tracking.TrackingContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** Runs a loopback-only HTTP MCP example and demonstrates explicit downstream attribution hooks. */
public final class SampleMcpServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SampleMcpServer.class.getName());
    private static final int MAX_BODY_BYTES = 65_536;
    private static final String PROTOCOL_VERSION = "2025-11-25";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Ga4Client client;
    private final McpAnalytics analytics;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Set<String> acceptedSearches = ConcurrentHashMap.newKeySet();
    private final Set<String> clickedSearches = ConcurrentHashMap.newKeySet();
    private final Set<String> transactions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> searches = new ConcurrentHashMap<>();
    private final Map<String, String> searchAgents = new ConcurrentHashMap<>();

    /** Starts an example server on an available loopback port, or on the requested port. */
    public SampleMcpServer(int port, Ga4Client client) throws IOException {
        this.client = client;
        this.analytics = new McpAnalytics(client);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.setExecutor(this.executor);
        this.server.createContext("/mcp", this::handleMcp);
        this.server.createContext("/click", this::handleClick);
        this.server.createContext("/demo/outcomes", this::handleDemoOutcomes);
        this.server.start();
    }

    /** Returns the address used by this example server. */
    public URI baseUri() { return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()); }

    /** Starts the example using credentials from environment variables. */
    public static void main(String[] arguments) throws IOException {
        Map<String, String> environment = System.getenv();
        Ga4Config.Builder configuration = Ga4Config.builder()
            .measurementId(required(environment, "GA4_MEASUREMENT_ID"))
            .apiSecret(required(environment, "GA4_API_SECRET"));
        if (environment.containsKey("GA4_ENDPOINT")) { configuration.endpoint(URI.create(environment.get("GA4_ENDPOINT"))); }
        Ga4Client client = new Ga4Client(configuration.build());
        SampleMcpServer server = new SampleMcpServer(Integer.parseInt(environment.getOrDefault("PORT", "8099")), client);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        LOGGER.info("Sample MCP server: " + server.baseUri() + "/mcp");
    }

    private void handleMcp(HttpExchange exchange) throws IOException {
        if (!this.isAllowedOrigin(exchange)) { this.respond(exchange, 403, Map.of("error", "Untrusted Origin or Host.")); return; }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().set("Allow", "POST");
            this.respond(exchange, 405, Map.of("error", "Use POST for this stateless example."));
            return;
        }
        long start = System.nanoTime();
        Map<String, Object> request = Map.of();
        String outcome = "success";
        TrackingContext context = this.context(exchange, "");
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                outcome = "invalid_request";
                this.respond(exchange, 413, error(null, -32600, "Request body is too large."));
                return;
            }
            try { request = this.mapper.readValue(body, new TypeReference<>() { }); }
            catch (IOException exception) {
                outcome = "parse_error";
                this.respond(exchange, 400, error(null, -32700, "Invalid JSON."));
                return;
            }
            if (request == null) { request = Map.of(); }
            Object requestId = request.get("id");
            if (!"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String)
                || (request.containsKey("id") && !(requestId instanceof String) && !(requestId instanceof Number))) {
                outcome = "invalid_request";
                this.respond(exchange, 400, error(requestId instanceof String || requestId instanceof Number ? requestId : null, -32600, "Invalid JSON-RPC request."));
                return;
            }
            if (!request.containsKey("id")) { this.respond(exchange, 202, null); return; }
            String method = request.get("method").toString();
            Map<?, ?> parameters = request.get("params") instanceof Map<?, ?> map ? map : Map.of();
            Object result;
            switch (method) {
                case "initialize" -> result = Map.of("protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "mcp-ga4-example", "version", "0.1.0"));
                case "ping" -> result = Map.of();
                case "tools/list" -> result = Map.of("tools", List.of(tool("search", "Search example products.", "query"),
                    tool("find_page", "Find a page about a topic.", "topic")));
                case "tools/call" -> {
                    String name = String.valueOf(parameters.get("name"));
                    if (!name.equals("search") && !name.equals("find_page")) {
                        outcome = "protocol_error";
                        this.respond(exchange, 200, error(request.get("id"), -32602, "Unknown tool."));
                        return;
                    }
                    Map<?, ?> arguments = parameters.get("arguments") instanceof Map<?, ?> map ? map : Map.of();
                    Object text = arguments.get(name.equals("search") ? "query" : "topic");
                    if (!(text instanceof String query) || query.isBlank()) {
                        outcome = "tool_error";
                        result = Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", "Provide a nonblank query or topic.")));
                    } else {
                        String searchId = UUID.randomUUID().toString();
                        if (this.searches.size() >= 1_000) { this.searches.clear(); this.clickedSearches.clear(); this.searchAgents.clear(); this.acceptedSearches.clear(); }
                        this.searches.put(searchId, query);
                        this.searchAgents.put(searchId, new McpAgentResolver().resolve(context, request, headers(exchange)).name());
                        context = this.context(exchange, searchId);
                        boolean page = name.equals("find_page");
                        if (!page) { this.acceptedSearches.add(searchId); }
                        this.analytics.search(context, query, page ? "page" : "search", !page, false);
                        if (!page) { this.analytics.event(context, "mcp_impression", Map.of("result_count", 1, "store", "Example Store", "position", 1)); }
                        String link = this.baseUri() + "/click?search_id=" + searchId;
                        result = Map.of("content", List.of(Map.of("type", "text", "text", "Example result: " + link)),
                            "structuredContent", Map.of("search_id", searchId, "url", link), "isError", false);
                    }
                }
                default -> {
                    outcome = "protocol_error";
                    this.respond(exchange, 200, error(request.get("id"), -32601, "Method not found."));
                    return;
                }
            }
            this.respond(exchange, 200, Map.of("jsonrpc", "2.0", "id", request.get("id"), "result", result));
        } finally {
            this.analytics.request(context, request, headers(exchange), outcome, Duration.ofNanos(System.nanoTime() - start).toMillis());
        }
    }

    private void handleClick(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { this.respond(exchange, 405, Map.of("error", "Use GET.")); return; }
        String searchId = queryParameter(exchange, "search_id");
        if (!this.searches.containsKey(searchId)) { this.respond(exchange, 404, Map.of("error", "Unknown search.")); return; }
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        String browserId = cookieValue(cookie, "sample_browser_id");
        if (browserId.isEmpty()) {
            browserId = UUID.randomUUID().toString();
            exchange.getResponseHeaders().add("Set-Cookie", "sample_browser_id=" + browserId + "; HttpOnly; SameSite=Lax; Path=/");
        }
        TrackingContext context = TrackingContext.builder().serverName("mcp-ga4-example").searchId(searchId)
            .conversationId(searchId).browserId(browserId).agentName(this.searchAgents.get(searchId)).source("example").build();
        this.analytics.linkOpened(context, "Example Store", "plain", "organic", this.acceptedSearches.contains(searchId) && this.clickedSearches.add(searchId));
        this.respond(exchange, 200, Map.of("message", "Example link opened.", "search_id", searchId));
    }

    private void handleDemoOutcomes(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("POST")) { this.respond(exchange, 405, Map.of("error", "Use POST.")); return; }
        String searchId = queryParameter(exchange, "search_id");
        String transactionId = queryParameter(exchange, "transaction_id");
        if (!this.searches.containsKey(searchId) || transactionId.isBlank()) {
            this.respond(exchange, 400, Map.of("error", "Provide a known search_id and unique transaction_id.")); return;
        }
        if (!this.transactions.add(transactionId)) { this.respond(exchange, 200, Map.of("duplicate", true)); return; }
        TrackingContext context = this.context(exchange, searchId);
        this.analytics.event(context, "mcp_cart", Map.of("cart_count", 1));
        this.analytics.event(context, "mcp_product_view", Map.of("product_view_count", 1));
        this.analytics.event(context, "mcp_result_view", Map.of("query_pageview_count", 1));
        this.analytics.purchase(context, transactionId, new BigDecimal("24.50"), "USD", 2);
        this.respond(exchange, 200, Map.of("recorded", true, "transaction_id", transactionId));
    }

    private boolean isAllowedOrigin(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        int port = this.server.getAddress().getPort();
        if (host == null || !Set.of("127.0.0.1:" + port, "localhost:" + port, "[::1]:" + port).contains(host)) { return false; }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) { return true; }
        return Set.of("http://127.0.0.1:" + port, "http://localhost:" + port, "http://[::1]:" + port).contains(origin);
    }

    private TrackingContext context(HttpExchange exchange, String searchId) {
        TrackingContext.Builder builder = TrackingContext.builder().serverName("mcp-ga4-example").source("example");
        if (!searchId.isBlank()) { builder.searchId(searchId).conversationId(searchId).agentName(this.searchAgents.get(searchId)); }
        String sessionId = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId != null && !sessionId.isBlank()) { builder.sessionId(sessionId); }
        return builder.build();
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        if (body == null) { exchange.sendResponseHeaders(status, -1); exchange.close(); return; }
        byte[] bytes = this.mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0"); response.put("id", id); response.put("error", Map.of("code", code, "message", message));
        return response;
    }

    private static Map<String, Object> tool(String name, String description, String parameter) {
        return Map.of("name", name, "description", description, "inputSchema", Map.of("type", "object",
            "properties", Map.of(parameter, Map.of("type", "string")), "required", List.of(parameter)));
    }

    private static Map<String, String> headers(HttpExchange exchange) {
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((key, values) -> headers.put(key, values.get(0)));
        return headers;
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) { return ""; }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) { return URLDecoder.decode(pair[1], StandardCharsets.UTF_8); }
        }
        return "";
    }

    private static String cookieValue(String header, String name) {
        if (header == null) { return ""; }
        for (String part : header.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(name) && pair[1].matches("[a-zA-Z0-9-]{1,64}")) { return pair[1]; }
        }
        return "";
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) { throw new IllegalArgumentException("Set environment variable " + name + "."); }
        return value;
    }

    /** Stops this sample and flushes its telemetry within the client's configured bound. */
    @Override public void close() { this.server.stop(0); this.executor.shutdownNow(); this.client.close(); }
}
