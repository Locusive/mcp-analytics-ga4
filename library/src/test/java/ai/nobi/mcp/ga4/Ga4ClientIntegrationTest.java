package ai.nobi.mcp.ga4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises real HTTP delivery, bounded queuing, timeouts, and graceful shutdown. */
class Ga4ClientIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<CapturedRequest> received = new LinkedBlockingQueue<>();
    private final List<Ga4Client> clients = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final List<DeliveryFailure> failures = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ExecutorService serverExecutor;
    private volatile ResponseHandler responseHandler = exchange -> exchange.sendResponseHeaders(204, -1);

    @BeforeEach
    void startCollector() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.serverExecutor = Executors.newCachedThreadPool();
        this.server.setExecutor(this.serverExecutor);
        this.server.createContext("/mp/collect", this::handle);
        this.server.start();
    }

    @AfterEach
    void closeResources() {
        this.gates.forEach(CountDownLatch::countDown);
        this.clients.forEach(Ga4Client::close);
        this.server.stop(0);
        this.serverExecutor.shutdownNow();
    }

    @Test
    void sendsAValidWebStreamPostWithOriginalTimeAndTypedParameters() throws Exception {
        Ga4Client client = this.client(this.config().apiSecret("fixture&secret +?/"));
        Instant timestamp = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AnalyticsEvent event = new AnalyticsEvent(
                "mcp_request", "anon-client", timestamp,
                Map.of("tool_name", "search", "duration_ms", 42L, "success", true,
                        "value", new BigDecimal("12.50"), "session_id", 12345), "anon-user");

        assertTrue(client.track(event));
        assertTrue(client.flush(Duration.ofSeconds(3)));
        CapturedRequest request = this.nextRequest();
        assertEquals("POST", request.method());
        assertEquals("application/json", request.contentType());
        assertTrue(request.query().contains("measurement_id=G-TEST123"));
        assertTrue(request.query().contains("api_secret=fixture%26secret+%2B%3F%2F"));
        assertEquals("anon-client", request.body().path("client_id").asText());
        assertEquals("anon-user", request.body().path("user_id").asText());
        assertEquals(AnalyticsEvent.timestampMicros(timestamp), request.body().path("timestamp_micros").asLong());
        JsonNode parameters = request.body().path("events").get(0).path("params");
        assertEquals("search", parameters.path("tool_name").asText());
        assertTrue(parameters.path("duration_ms").isNumber());
        assertEquals(42, parameters.path("duration_ms").asInt());
        assertEquals(1, parameters.path("success").asInt());
        assertEquals(12.5, parameters.path("value").asDouble());
        assertEquals(new Ga4Stats(1, 1, 0, 0), client.stats());
    }

    @Test
    void batchesAtMost25EventsWithoutChangingCounts() throws Exception {
        CountDownLatch release = this.blockFirstRequest();
        Ga4Client client = this.client(this.config());
        assertTrue(client.track(event("blocking", Instant.now(), null)));
        this.nextRequest();
        Instant timestamp = Instant.now();

        for (int index = 0; index < 61; index++) {
            assertTrue(client.track(event("batch", timestamp, null)));
        }

        release.countDown();
        assertTrue(client.flush(Duration.ofSeconds(3)));
        int count = 0;
        List<Integer> sizes = new ArrayList<>();

        while (count < 61) {
            CapturedRequest request = this.nextRequest();
            int size = request.body().path("events").size();
            assertTrue(size <= 25);
            assertEquals("batch", request.body().path("client_id").asText());
            count += size;
            sizes.add(size);
        }

        assertEquals(List.of(25, 25, 11), sizes);
        assertEquals(new Ga4Stats(62, 62, 0, 0), client.stats());
    }

    @Test
    void separatesClientUserAndTimestampGroups() throws Exception {
        CountDownLatch release = this.blockFirstRequest();
        Ga4Client client = this.client(this.config());
        client.track(event("blocking", Instant.now(), null));
        this.nextRequest();
        Instant firstTime = Instant.now();
        Instant secondTime = firstTime.plusMillis(1);
        client.track(event("client-a", firstTime, null));
        client.track(event("client-a", firstTime, null));
        client.track(event("client-b", firstTime, null));
        client.track(event("client-b", secondTime, null));
        client.track(event("client-b", secondTime, "user-b"));
        release.countDown();

        assertTrue(client.flush(Duration.ofSeconds(3)));
        CapturedRequest first = this.nextRequest();
        assertEquals(2, first.body().path("events").size());
        assertEquals("client-a", first.body().path("client_id").asText());
        assertEquals("client-b", this.nextRequest().body().path("client_id").asText());
        assertEquals(AnalyticsEvent.timestampMicros(secondTime), this.nextRequest().body().path("timestamp_micros").asLong());
        assertEquals("user-b", this.nextRequest().body().path("user_id").asText());
        assertEquals(6, client.stats().delivered());
    }

    @Test
    void splitsLargeUnicodeBatchesBelowThePostBodyLimit() throws Exception {
        CountDownLatch release = this.blockFirstRequest();
        Ga4Client client = this.client(this.config());
        client.track(event("blocking", Instant.now(), null));
        this.nextRequest();
        Map<String, Object> parameters = new LinkedHashMap<>();

        for (int index = 0; index < 25; index++) {
            parameters.put("field_" + index, "😀".repeat(100));
        }

        Instant timestamp = Instant.now();

        for (int index = 0; index < 25; index++) {
            assertTrue(client.track(new AnalyticsEvent("mcp_request", "large", timestamp, parameters)));
        }

        release.countDown();
        assertTrue(client.flush(Duration.ofSeconds(3)));
        int delivered = 0;

        while (delivered < 25) {
            CapturedRequest request = this.nextRequest();
            assertTrue(request.byteCount() < 130_000);
            delivered += request.body().path("events").size();
        }

        assertEquals(25, delivered);
        assertEquals(26, client.stats().delivered());
    }

    @Test
    void refusesFullQueuesWithoutWaitingForTheNetwork() throws Exception {
        CountDownLatch release = this.blockFirstRequest();
        Ga4Client client = this.client(this.config().queueCapacity(1));
        assertTrue(client.track(event("first", Instant.now(), null)));
        this.nextRequest();
        assertTrue(client.track(event("second", Instant.now(), null)));
        assertTimeout(Duration.ofMillis(250), () -> assertFalse(client.track(event("third", Instant.now(), null))));
        assertFalse(client.flush(Duration.ZERO));
        release.countDown();

        assertTrue(client.flush(Duration.ofSeconds(3)));
        assertEquals(new Ga4Stats(2, 2, 1, 0), client.stats());
    }

    @Test
    void reportsHttpFailuresWithoutRetriesAndContinuesWithLaterEvents() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        this.responseHandler = exchange -> exchange.sendResponseHeaders(requests.incrementAndGet() == 1 ? 503 : 204, -1);
        Ga4Client client = this.client(this.config().failureListener(failure -> {
            this.failures.add(failure);
            throw new IllegalStateException("Caller callback failed.");
        }));
        client.track(event("first", Instant.now(), null));
        client.track(event("second", Instant.now(), null));

        assertTrue(client.flush(Duration.ofSeconds(3)));
        client.close();
        assertEquals(2, requests.get());
        assertEquals(new Ga4Stats(2, 1, 0, 1), client.stats());
        assertEquals(List.of(new DeliveryFailure(DeliveryFailure.Reason.HTTP_ERROR, 1, 503)), this.failures);
        assertFalse(this.failures.toString().contains("fixture-secret"));
    }

    @Test
    void timesOutAnAcceptedRequestWithoutDuplicatingIt() throws Exception {
        CountDownLatch release = this.blockFirstRequest();
        Ga4Client client = this.client(this.config().requestTimeout(Duration.ofMillis(200)));
        assertTrue(client.track(event("client", Instant.now(), null)));
        this.nextRequest();

        assertTrue(client.flush(Duration.ofSeconds(3)));
        client.close();
        assertEquals(new Ga4Stats(1, 0, 0, 1), client.stats());
        assertEquals(DeliveryFailure.Reason.TIMEOUT, this.failures.get(0).reason());
        assertTrue(this.received.isEmpty());
        release.countDown();
    }

    @Test
    void timesOutAResponseThatNeverFinishesItsBody() throws Exception {
        CountDownLatch release = this.gate();
        this.responseHandler = exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('x');
            exchange.getResponseBody().flush();
            await(release);
        };
        Ga4Client client = this.client(this.config().requestTimeout(Duration.ofMillis(200)));
        client.track(event("client", Instant.now(), null));

        assertTrue(client.flush(Duration.ofSeconds(3)));
        client.close();
        assertEquals(DeliveryFailure.Reason.TIMEOUT, this.failures.get(0).reason());
        assertEquals(1, client.stats().failed());
        release.countDown();
    }

    @Test
    void doesNotFollowRedirectsOrLeakCredentialsToAnotherPath() throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        this.server.createContext("/other", exchange -> {
            redirected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        this.responseHandler = exchange -> {
            exchange.getResponseHeaders().set("Location", "/other");
            exchange.sendResponseHeaders(307, -1);
        };
        Ga4Client client = this.client(this.config());
        client.track(event("client", Instant.now(), null));

        assertTrue(client.flush(Duration.ofSeconds(3)));
        client.close();
        assertEquals(0, redirected.get());
        assertEquals(new DeliveryFailure(DeliveryFailure.Reason.HTTP_ERROR, 1, 307), this.failures.get(0));
    }

    @Test
    void closesWithinItsBoundAndAccountsForAbandonedEvents() throws Exception {
        this.blockFirstRequest();
        Ga4Client client = this.client(this.config().requestTimeout(Duration.ofSeconds(10)).shutdownTimeout(Duration.ofMillis(100)));
        client.track(event("first", Instant.now(), null));
        this.nextRequest();
        client.track(event("second", Instant.now(), null));

        assertTimeout(Duration.ofSeconds(1), client::close);
        assertTrue(client.flush(Duration.ofSeconds(1)));
        assertEquals(new Ga4Stats(2, 0, 0, 2), client.stats());
        assertFalse(client.track(event("after-close", Instant.now(), null)));
        client.close();
        assertEquals(1, client.stats().rejected());
    }

    @Test
    void rejectsStaleFutureAndNullEventsBeforeQueueAcceptance() {
        Ga4Client client = this.client(this.config());
        assertFalse(client.track(event("stale", Instant.now().minus(Duration.ofHours(73)), null)));
        assertFalse(client.track(event("future", Instant.now().plus(Duration.ofMinutes(2)), null)));
        assertFalse(client.track(null));
        assertTrue(client.flush(Duration.ZERO));
        assertEquals(new Ga4Stats(0, 0, 3, 0), client.stats());
        assertTrue(this.received.isEmpty());
    }

    @Test
    void closeDrainsAcceptedEventsAndNetworkErrorsAreTerminal() throws Exception {
        Ga4Client client = this.client(this.config());
        client.track(event("normal", Instant.now(), null));
        client.close();
        assertEquals(new Ga4Stats(1, 1, 0, 0), client.stats());
        this.server.stop(0);
        Ga4Client disconnected = this.client(this.config());
        disconnected.track(event("offline", Instant.now(), null));
        assertTrue(disconnected.flush(Duration.ofSeconds(3)));
        disconnected.close();
        assertEquals(new Ga4Stats(1, 0, 0, 1), disconnected.stats());
        assertEquals(DeliveryFailure.Reason.NETWORK_ERROR, this.failures.get(0).reason());
    }

    /** Handles an actual HTTP exchange without contacting any external analytics service. */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            this.received.add(new CapturedRequest(
                    exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders().getFirst("Content-Type"), this.mapper.readTree(bytes), bytes.length));
            this.responseHandler.respond(exchange);
        } finally {
            exchange.close();
        }
    }

    /** Creates a sender and registers it for unconditional cleanup. */
    private Ga4Client client(Ga4Config.Builder settings) {
        Ga4Client client = new Ga4Client(settings.build());
        this.clients.add(client);
        return client;
    }

    /** Creates settings pointing only to this test's loopback HTTP collector. */
    private Ga4Config.Builder config() {
        return Ga4Config.builder().measurementId("G-TEST123").apiSecret("fixture-secret")
                .endpoint(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + "/mp/collect"))
                .requestTimeout(Duration.ofSeconds(2)).shutdownTimeout(Duration.ofSeconds(2))
                .failureListener(this.failures::add);
    }

    /** Waits for an independently observed request with a finite test deadline. */
    private CapturedRequest nextRequest() throws InterruptedException {
        CapturedRequest request = this.received.poll(3, TimeUnit.SECONDS);
        assertNotNull(request, "The collector did not receive the expected HTTP request.");
        return request;
    }

    /** Blocks only the first HTTP response so subsequent events can fill or batch in the queue. */
    private CountDownLatch blockFirstRequest() {
        CountDownLatch release = this.gate();
        AtomicInteger count = new AtomicInteger();
        this.responseHandler = exchange -> {
            if (count.incrementAndGet() == 1) {
                await(release);
            }

            exchange.sendResponseHeaders(204, -1);
        };
        return release;
    }

    /** Registers a response gate that cleanup releases even when an assertion fails. */
    private CountDownLatch gate() {
        CountDownLatch release = new CountDownLatch(1);
        this.gates.add(release);
        return release;
    }

    /** Waits on a server response gate without leaving an unbounded test thread. */
    private static void await(CountDownLatch release) throws IOException {
        try {
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IOException("The test response gate timed out.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("The test response gate was interrupted.");
        }
    }

    /** Creates a minimal request event with optional request-level user identity. */
    private static AnalyticsEvent event(String clientId, Instant timestamp, String userId) {
        return new AnalyticsEvent("mcp_request", clientId, timestamp, Map.of("method", "tools/call"), userId);
    }

    /** Captures public wire behavior for functional assertions. */
    private record CapturedRequest(String method, String query, String contentType, JsonNode body, int byteCount) {
    }

    /** Supplies the real collector response needed by each integration scenario. */
    private interface ResponseHandler {
        /** Writes the collector response. */
        void respond(HttpExchange exchange) throws IOException;
    }
}
