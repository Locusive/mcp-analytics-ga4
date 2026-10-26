package ai.nobi.mcp.ga4;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A bounded, asynchronous GA4 Measurement Protocol sender for web streams.
 * Enqueueing never performs network I/O. HTTP 2xx means receipt, not GA4 processing.
 * Requests are never retried: Google's transport guidance forbids retrying the same
 * rejected request, and ambiguous network outcomes could otherwise duplicate events.
 */
public final class Ga4Client implements AutoCloseable {
    private static final int MaxBatchSize = 25;
    private static final int MaxBodyBytes = 129_999;
    private static final Duration MaxEventAge = Duration.ofHours(72);
    private static final Duration MaxFutureClockSkew = Duration.ofMinutes(1);
    private static final long QueuePollMillis = 100;
    private static final long ShutdownJoinMillis = 100;

    private final Ga4Config config;
    private final ArrayBlockingQueue<AnalyticsEvent> queue;
    private final Object monitor = new Object();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final Thread worker;
    private final URI collectionUri;

    private boolean closed;
    private volatile boolean stopped;
    private long accepted;
    private long delivered;
    private long rejected;
    private long failed;

    /**
     * Starts a daemon worker using the provided validated settings.
     *
     * @param config immutable transport configuration
     */
    public Ga4Client(Ga4Config config) {
        this.config = Objects.requireNonNull(config, "GA4 configuration is required.");
        this.queue = new ArrayBlockingQueue<>(config.queueCapacity());
        this.collectionUri = Ga4Client.collectionUri(config);
        this.httpExecutor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "mcp-ga4-http");
            thread.setDaemon(true);
            return thread;
        });
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(config.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(this.httpExecutor)
                .build();
        this.worker = new Thread(this::runWorker, "mcp-ga4-delivery");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Enqueues an event without waiting for capacity or performing network I/O.
     * Events older than 72 hours or over one minute in the future are rejected.
     *
     * @param event an immutable, validated event
     * @return true when locally accepted, false when closed, full, null, or outside the time window
     */
    public boolean track(AnalyticsEvent event) {
        synchronized (this.monitor) {
            if (this.closed || !Ga4Client.isCurrent(event) || !this.queue.offer(event)) {
                this.rejected++;
                return false;
            }

            this.accepted++;
            return true;
        }
    }

    /**
     * Waits for outstanding events to reach an HTTP success or terminal failure.
     * Events accepted during the wait may extend it up to the supplied timeout.
     * A drained queue does not mean events were processed by GA4; inspect diagnostics and reports.
     *
     * @param timeout the maximum duration to wait, including zero for an immediate check
     * @return true if all previously accepted events have completed, including failed events
     */
    public boolean flush(Duration timeout) {
        long remaining = Ga4Client.timeoutNanos(timeout);
        long started = System.nanoTime();

        synchronized (this.monitor) {
            while (this.delivered + this.failed < this.accepted) {
                if (remaining <= 0) {
                    return false;
                }

                try {
                    TimeUnit.NANOSECONDS.timedWait(this.monitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }

                remaining = Ga4Client.timeoutNanos(timeout) - (System.nanoTime() - started);
            }

            return true;
        }
    }

    /** @return a consistent snapshot of event-level queue and HTTP outcome counts */
    public Ga4Stats stats() {
        synchronized (this.monitor) {
            return new Ga4Stats(this.accepted, this.delivered, this.rejected, this.failed);
        }
    }

    /**
     * Stops acceptance, drains for the configured shutdown timeout, then cancels remaining work.
     * This method is idempotent. Callers can inspect failed counts after a bounded shutdown.
     */
    @Override
    public void close() {
        synchronized (this.monitor) {
            if (this.closed) {
                return;
            }

            this.closed = true;
        }

        if (Thread.currentThread() != this.worker) {
            this.flush(this.config.shutdownTimeout());
        }

        this.stopped = true;
        this.worker.interrupt();
        this.discardQueued();
        this.httpExecutor.shutdownNow();

        if (Thread.currentThread() != this.worker) {
            this.awaitWorkerExit();
        }
    }

    /** Runs the single delivery worker and isolates expected failures from the MCP application. */
    private void runWorker() {
        while (!this.stopped) {
            try {
                AnalyticsEvent first = this.queue.poll(QueuePollMillis, TimeUnit.MILLISECONDS);

                if (first != null) {
                    this.deliver(this.collectBatch(first));
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Groups only adjacent events with the same identity and original occurrence time. */
    private List<AnalyticsEvent> collectBatch(AnalyticsEvent first) {
        List<AnalyticsEvent> batch = new ArrayList<>();
        batch.add(first);

        while (batch.size() < MaxBatchSize) {
            AnalyticsEvent next = this.queue.peek();

            if (next == null || !Ga4Client.canShareRequest(first, next)) {
                break;
            }

            batch.add(next);

            try {
                if (this.encode(batch).length > MaxBodyBytes) {
                    batch.remove(batch.size() - 1);
                    break;
                }
            } catch (JsonProcessingException exception) {
                batch.remove(batch.size() - 1);
                break;
            }

            if (!this.queue.remove(next)) {
                batch.remove(batch.size() - 1);
                break;
            }
        }

        return batch;
    }

    /** Performs one bounded HTTP attempt and reports a safe outcome. */
    private void deliver(List<AnalyticsEvent> batch) {
        byte[] body;

        try {
            body = this.encode(batch);
        } catch (JsonProcessingException exception) {
            this.finish(batch.size(), new DeliveryFailure(DeliveryFailure.Reason.INVALID_PAYLOAD, batch.size(), 0));
            return;
        }

        if (body.length > MaxBodyBytes) {
            this.finish(batch.size(), new DeliveryFailure(DeliveryFailure.Reason.INVALID_PAYLOAD, batch.size(), 0));
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(this.collectionUri)
                .timeout(this.config.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        CompletableFuture<HttpResponse<Void>> pending = null;

        try {
            pending = this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            HttpResponse<Void> response = pending.get(this.config.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
            int status = response.statusCode();
            DeliveryFailure failure = status >= 200 && status < 300 ? null
                    : new DeliveryFailure(DeliveryFailure.Reason.HTTP_ERROR, batch.size(), status);
            this.finish(batch.size(), failure);
        } catch (TimeoutException exception) {
            this.finish(batch.size(), new DeliveryFailure(DeliveryFailure.Reason.TIMEOUT, batch.size(), 0));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.finish(batch.size(), new DeliveryFailure(DeliveryFailure.Reason.SHUTDOWN, batch.size(), 0));
        } catch (ExecutionException exception) {
            DeliveryFailure.Reason reason = exception.getCause() instanceof HttpTimeoutException
                    ? DeliveryFailure.Reason.TIMEOUT : DeliveryFailure.Reason.NETWORK_ERROR;
            this.finish(batch.size(), new DeliveryFailure(reason, batch.size(), 0));
        } catch (RuntimeException exception) {
            this.finish(batch.size(), new DeliveryFailure(DeliveryFailure.Reason.NETWORK_ERROR, batch.size(), 0));
        } finally {
            if (pending != null && !pending.isDone()) {
                pending.cancel(true);
            }
        }
    }

    /** Encodes the GA4 web stream request with a shared identity and timestamp. */
    private byte[] encode(List<AnalyticsEvent> batch) throws JsonProcessingException {
        AnalyticsEvent first = batch.get(0);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_id", first.clientId());
        payload.put("timestamp_micros", AnalyticsEvent.timestampMicros(first.timestamp()));

        if (first.userId() != null) {
            payload.put("user_id", first.userId());
        }

        List<Map<String, Object>> events = new ArrayList<>();

        for (AnalyticsEvent event : batch) {
            events.add(Map.of("name", event.name(), "params", event.parameters()));
        }

        payload.put("events", events);
        return this.mapper.writeValueAsBytes(payload);
    }

    /** Updates counters before invoking an isolated callback with safe failure details. */
    private void finish(int count, DeliveryFailure failure) {
        synchronized (this.monitor) {
            if (failure == null) {
                this.delivered += count;
            } else {
                this.failed += count;
            }

            this.monitor.notifyAll();
        }

        if (failure != null) {
            try {
                this.config.failureListener().accept(failure);
            } catch (RuntimeException exception) {
                // A caller's diagnostic callback cannot stop analytics delivery.
            }
        }
    }

    /** Marks queued events as failed without blocking close on a user callback. */
    private void discardQueued() {
        List<AnalyticsEvent> discarded = new ArrayList<>();
        this.queue.drainTo(discarded);

        synchronized (this.monitor) {
            this.failed += discarded.size();
            this.monitor.notifyAll();
        }
    }

    /** Gives the interrupted worker a short bounded opportunity to publish its final counters. */
    private void awaitWorkerExit() {
        try {
            this.worker.join(ShutdownJoinMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Builds the only credential-bearing URI without exposing it through public diagnostics. */
    private static URI collectionUri(Ga4Config config) {
        String measurementId = URLEncoder.encode(config.measurementId(), StandardCharsets.UTF_8);
        String secret = URLEncoder.encode(config.apiSecret(), StandardCharsets.UTF_8);
        return URI.create(config.endpoint() + "?measurement_id=" + measurementId + "&api_secret=" + secret);
    }

    /** Checks time limits at enqueue time so backdating cannot silently shift reported dates. */
    private static boolean isCurrent(AnalyticsEvent event) {
        if (event == null) {
            return false;
        }

        Instant now = Instant.now();
        return !event.timestamp().isBefore(now.minus(MaxEventAge))
                && !event.timestamp().isAfter(now.plus(MaxFutureClockSkew));
    }

    /** Requires equality of every request-level field before sharing a GA4 request. */
    private static boolean canShareRequest(AnalyticsEvent first, AnalyticsEvent next) {
        return first.clientId().equals(next.clientId()) && first.timestamp().equals(next.timestamp())
                && Objects.equals(first.userId(), next.userId());
    }

    /** Converts a nonnegative timeout while protecting worker waits from overflow. */
    private static long timeoutNanos(Duration timeout) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalArgumentException("A nonnegative flush timeout is required.");
        }

        try {
            return timeout.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("The flush timeout exceeds the supported range.");
        }
    }
}
