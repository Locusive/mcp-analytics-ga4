package ai.nobi.mcp.ga4;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Immutable transport configuration whose string representation never exposes credentials. */
public final class Ga4Config {
    private static final URI DefaultEndpoint = URI.create("https://www.google-analytics.com/mp/collect");
    private static final Duration DefaultRequestTimeout = Duration.ofSeconds(5);
    private static final Duration DefaultShutdownTimeout = Duration.ofSeconds(5);
    private static final int DefaultQueueCapacity = 1_024;
    private static final Pattern MeasurementIdPattern = Pattern.compile("G-[A-Z0-9]+");

    private final String measurementId;
    private final String apiSecret;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final Duration shutdownTimeout;
    private final int queueCapacity;
    private final Consumer<DeliveryFailure> failureListener;

    /** Validates and freezes builder values. */
    private Ga4Config(Builder builder) {
        if (builder.measurementId == null || !MeasurementIdPattern.matcher(builder.measurementId).matches()) {
            throw new IllegalArgumentException("A GA4 web stream measurement ID starting with G- is required.");
        }

        if (builder.apiSecret == null || builder.apiSecret.isBlank()) {
            throw new IllegalArgumentException("A Measurement Protocol API secret is required.");
        }

        if (builder.queueCapacity < 1) {
            throw new IllegalArgumentException("The queue capacity must be positive.");
        }

        this.measurementId = builder.measurementId;
        this.apiSecret = builder.apiSecret;
        this.endpoint = Ga4Config.validateEndpoint(builder.endpoint);
        this.requestTimeout = Ga4Config.validateDuration(builder.requestTimeout);
        this.shutdownTimeout = Ga4Config.validateDuration(builder.shutdownTimeout);
        this.queueCapacity = builder.queueCapacity;
        this.failureListener = Objects.requireNonNull(builder.failureListener, "A failure listener is required.");
    }

    /** @return a builder with bounded queue and timeout defaults */
    public static Builder builder() {
        return new Builder();
    }

    /** @return the web stream measurement ID */
    public String measurementId() {
        return this.measurementId;
    }

    /** @return the collection endpoint without query credentials */
    public URI endpoint() {
        return this.endpoint;
    }

    /** @return the maximum duration of one complete HTTP exchange */
    public Duration requestTimeout() {
        return this.requestTimeout;
    }

    /** @return the maximum wait for draining during close */
    public Duration shutdownTimeout() {
        return this.shutdownTimeout;
    }

    /** @return the maximum number of waiting events */
    public int queueCapacity() {
        return this.queueCapacity;
    }

    /** Returns the secret exclusively to the transport implementation. */
    String apiSecret() {
        return this.apiSecret;
    }

    /** Returns the optional failure callback, which must execute promptly. */
    Consumer<DeliveryFailure> failureListener() {
        return this.failureListener;
    }

    /** Rejects insecure remote endpoints and credentials supplied inside endpoint URLs. */
    private static URI validateEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "A collection endpoint is required.");
        String host = endpoint.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        boolean secure = "https".equalsIgnoreCase(endpoint.getScheme());
        boolean localHttp = local && "http".equalsIgnoreCase(endpoint.getScheme());

        if (host == null || (!secure && !localHttp) || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Collection endpoints require HTTPS, a host, and no credentials, query, or fragment; localhost HTTP is allowed for testing.");
        }

        return endpoint;
    }

    /** Ensures timeout conversions cannot overflow and waits remain bounded. */
    private static Duration validateDuration(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("Timeouts must be positive durations.");
        }

        try {
            duration.toNanos();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Timeouts exceed the supported duration range.");
        }

        return duration;
    }

    /** Builds transport settings without reading environment variables or system properties. */
    public static final class Builder {
        private String measurementId;
        private String apiSecret;
        private URI endpoint = DefaultEndpoint;
        private Duration requestTimeout = DefaultRequestTimeout;
        private Duration shutdownTimeout = DefaultShutdownTimeout;
        private int queueCapacity = DefaultQueueCapacity;
        private Consumer<DeliveryFailure> failureListener = failure -> { };

        /** Creates a builder through {@link Ga4Config#builder()}. */
        private Builder() {
        }

        /**
         * Sets the GA4 web stream identifier.
         * @param value the GA4 web stream ID
         * @return this builder
         */
        public Builder measurementId(String value) {
            this.measurementId = value;
            return this;
        }

        /**
         * Sets the private server-side Measurement Protocol secret.
         * @param value the private API secret
         * @return this builder
         */
        public Builder apiSecret(String value) {
            this.apiSecret = value;
            return this;
        }

        /**
         * Sets the collection endpoint.
         * @param value an HTTPS collection URL, or localhost HTTP for testing
         * @return this builder
         */
        public Builder endpoint(URI value) {
            this.endpoint = value;
            return this;
        }

        /**
         * Sets the timeout covering response headers and body.
         * @param value the complete HTTP exchange timeout
         * @return this builder
         */
        public Builder requestTimeout(Duration value) {
            this.requestTimeout = value;
            return this;
        }

        /**
         * Sets the maximum wait for outstanding events on close.
         * @param value the maximum shutdown drain duration
         * @return this builder
         */
        public Builder shutdownTimeout(Duration value) {
            this.shutdownTimeout = value;
            return this;
        }

        /**
         * Sets the maximum number of waiting events.
         * @param value the queue capacity
         * @return this builder
         */
        public Builder queueCapacity(int value) {
            this.queueCapacity = value;
            return this;
        }

        /**
         * Sets a callback that runs on the daemon delivery thread and must return promptly.
         * Callback exceptions are isolated from MCP request handling and future delivery.
         *
         * @param value the safe failure callback
         * @return this builder
         */
        public Builder failureListener(Consumer<DeliveryFailure> value) {
            this.failureListener = value;
            return this;
        }

        /** @return validated immutable settings */
        public Ga4Config build() {
            return new Ga4Config(this);
        }
    }
}
