package dev.stacklight.client;

/**
 * Reports errors to a Stacklight collector.
 *
 * <pre>{@code
 * StacklightClient client = StacklightClient.start(new StacklightOptions()
 *         .endpoint("https://collector.example.com/api/events")
 *         .apiKey(System.getenv("STACKLIGHT_KEY"))
 *         .service("checkout-api")
 *         .release("1.4.0"));
 *
 * try {
 *     doWork();
 * } catch (Exception e) {
 *     client.capture(e);
 * }
 * }</pre>
 *
 * <p>{@link #capture} enqueues and returns. It does not open a connection, does not wait
 * for one, and does not throw — the calling thread is usually in the middle of handling a
 * request that has already gone wrong, and making that worse is not an option available to
 * an error reporter.
 */
public final class StacklightClient implements AutoCloseable {

    private final StacklightOptions options;
    private final EventQueue queue;
    private final Dispatcher dispatcher;
    private final Logger logger;
    private final boolean enabled;

    private Thread shutdownHook;

    private StacklightClient(StacklightOptions options, Transport transport) {
        this.options = options;
        this.logger = new Logger(options.debug());
        this.enabled = options.isConfigured();
        this.queue = new EventQueue(options.queueCapacity());
        this.dispatcher = new Dispatcher(queue, transport, options, logger);
    }

    /** Starts a client. Without an endpoint and key it is inert rather than absent. */
    public static StacklightClient start(StacklightOptions options) {
        return start(options, null);
    }

    static StacklightClient start(StacklightOptions options, Transport transport) {
        StacklightClient client =
                new StacklightClient(
                        options, transport != null ? transport : new HttpTransport(options, new Logger(options.debug())));

        if (!client.enabled) {
            // Not an error. An application should be able to run in an environment with
            // no collector configured without that being something it has to handle.
            client.logger.debug(() -> "no endpoint or key configured; capture is a no-op");
            return client;
        }

        client.dispatcher.start();
        client.installShutdownHook();
        return client;
    }

    /** Reports a throwable at ERROR level. */
    public void capture(Throwable throwable) {
        capture(throwable, "ERROR");
    }

    public void capture(Throwable throwable, String level) {
        if (!enabled || throwable == null) {
            return;
        }
        try {
            queue.offer(StacklightEvent.from(throwable, level, options));
        } catch (RuntimeException e) {
            // Even building the event must not be able to propagate. Whatever went wrong
            // here matters less than the exception the caller was already dealing with.
            logger.debug(() -> "capture failed: " + e);
        }
    }

    /** Reports a message with no throwable behind it. */
    public void captureMessage(String message, String level) {
        if (!enabled || message == null || message.isBlank()) {
            return;
        }
        try {
            queue.offer(StacklightEvent.message(message, level, options));
        } catch (RuntimeException e) {
            logger.debug(() -> "capture failed: " + e);
        }
    }

    /** Sends what is queued, waiting up to the configured shutdown timeout. */
    public void flush() {
        if (enabled) {
            dispatcher.flush(options.shutdownTimeout().toMillis());
        }
    }

    public StacklightStats stats() {
        return new StacklightStats(
                queue.accepted(),
                dispatcher.sent(),
                queue.dropped() + dispatcher.givenUp(),
                queue.size(),
                dispatcher.failedAttempts(),
                dispatcher.lastError());
    }

    @Override
    public void close() {
        if (!enabled) {
            return;
        }
        removeShutdownHook();
        dispatcher.stop();
        dispatcher.flush(options.shutdownTimeout().toMillis());
    }

    /**
     * Makes a normal exit flush what is still queued.
     *
     * <p>Without it, the events most worth having — the ones from the failure that is
     * bringing the process down — are the ones still in memory when it goes.
     */
    private void installShutdownHook() {
        shutdownHook =
                new Thread(
                        () -> {
                            dispatcher.stop();
                            dispatcher.flush(options.shutdownTimeout().toMillis());
                        },
                        "stacklight-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // Already shutting down; nothing to install onto.
            shutdownHook = null;
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // Shutdown already in progress; the hook is running or has run.
        }
        shutdownHook = null;
    }
}
