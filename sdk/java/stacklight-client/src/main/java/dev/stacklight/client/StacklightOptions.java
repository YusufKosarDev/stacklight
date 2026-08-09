package dev.stacklight.client;

import java.time.Duration;

/**
 * How the client behaves.
 *
 * <p>The defaults are not guesses. The collector this talks to sleeps after fifteen idle
 * minutes and takes between 95 and 114 seconds to wake, and during one measured wake the
 * platform returned 503 rather than holding the connection open. Every timeout and delay
 * below is chosen against those numbers.
 */
public final class StacklightOptions {

    private String endpoint = "";
    private String apiKey = "";
    private String service = "unknown";
    private String release;
    private String platform = "java";

    private int queueCapacity = 512;
    private int batchSize = 20;

    // Short on purpose. The point is never to be the reason a request is slow; a
    // collector that has not woken up yet should fail fast and be retried, not held on to.
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(5);

    private Duration retryBaseDelay = Duration.ofSeconds(1);
    private Duration retryMaxDelay = Duration.ofSeconds(30);

    private Duration shutdownTimeout = Duration.ofSeconds(3);

    private boolean debug = false;

    public String endpoint() {
        return endpoint;
    }

    /** Full URL of the ingest endpoint, for example {@code https://host/api/events}. */
    public StacklightOptions endpoint(String endpoint) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        return this;
    }

    public String apiKey() {
        return apiKey;
    }

    public StacklightOptions apiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        return this;
    }

    public String service() {
        return service;
    }

    public StacklightOptions service(String service) {
        this.service = service == null || service.isBlank() ? "unknown" : service.trim();
        return this;
    }

    public String release() {
        return release;
    }

    public StacklightOptions release(String release) {
        this.release = release == null || release.isBlank() ? null : release.trim();
        return this;
    }

    public String platform() {
        return platform;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    /**
     * How many events may wait in memory.
     *
     * <p>Bounded because the alternative is not "no loss", it is an application that runs
     * out of heap because its error reporter would not let go of anything.
     */
    public StacklightOptions queueCapacity(int queueCapacity) {
        this.queueCapacity = Math.max(1, queueCapacity);
        return this;
    }

    public int batchSize() {
        return batchSize;
    }

    public StacklightOptions batchSize(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
        return this;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public StacklightOptions connectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public StacklightOptions requestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
        return this;
    }

    public Duration retryBaseDelay() {
        return retryBaseDelay;
    }

    public StacklightOptions retryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
        return this;
    }

    public Duration retryMaxDelay() {
        return retryMaxDelay;
    }

    /**
     * Ceiling on the wait between attempts.
     *
     * <p>Thirty seconds against a hundred-second wake means several attempts span the
     * cold start rather than one long one, so a collector that comes back early is used
     * early.
     */
    public StacklightOptions retryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
        return this;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    /** How long a final flush may take before the process is allowed to leave. */
    public StacklightOptions shutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
        return this;
    }

    public boolean debug() {
        return debug;
    }

    /** Prints what the client is doing to stderr. Off by default; it is not the app's noise. */
    public StacklightOptions debug(boolean debug) {
        this.debug = debug;
        return this;
    }

    /** Whether there is enough here to send anything at all. */
    public boolean isConfigured() {
        return !endpoint.isBlank() && !apiKey.isBlank();
    }
}
