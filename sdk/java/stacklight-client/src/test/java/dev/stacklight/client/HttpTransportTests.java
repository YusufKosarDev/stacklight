package dev.stacklight.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The transport against a real server, using the one the JDK already ships so the client
 * keeps its promise of dragging in nothing.
 *
 * <p>The judgements worth testing are which status codes deserve another attempt, and how a
 * partly-accepted batch is sorted: the collector answers 202 and says per event what became
 * of it, so "the request worked" and "every event landed" are no longer the same question.
 */
class HttpTransportTests {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new ArrayList<>();
    private final List<Integer> sizes = new ArrayList<>();

    /** Answered instead of 202 when set above zero. */
    private volatile int status = 202;

    /** Given an event's index, returns the result fields for it, or null to accept it. */
    private volatile IntFunction<String> mark = index -> null;

    /** Answered verbatim instead of a well-formed receipt, for the unreadable case. */
    private volatile String rawBody = null;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/events",
                exchange -> {
                    requests.incrementAndGet();
                    paths.add(exchange.getRequestURI().getPath());

                    String body;
                    try (InputStream in = exchange.getRequestBody()) {
                        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }

                    if (status != 202) {
                        exchange.sendResponseHeaders(status, -1);
                        exchange.close();
                        return;
                    }

                    int count = countEvents(body);
                    sizes.add(count);

                    String answer = rawBody != null ? rawBody : receipt(count);
                    byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(202, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    /** Counts top-level objects by counting the event ids the client wrote. */
    private static int countEvents(String body) {
        return (int) body.chars().filter(c -> c == '{').count();
    }

    private String receipt(int count) {
        StringBuilder out = new StringBuilder("{\"accepted\":" + count + ",\"results\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(',');
            }
            String failure = mark.apply(i);
            out.append('{')
                    .append(failure != null ? failure : "\"stored\":true,\"error\":null,\"retryable\":false")
                    .append('}');
        }
        return out.append("]}").toString();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private StacklightOptions options() {
        return new StacklightOptions()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/api/events")
                .apiKey("test-key")
                .service("checkout-api");
    }

    private List<StacklightEvent> batch(int size) {
        StacklightOptions options = options();
        List<StacklightEvent> events = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            events.add(StacklightEvent.message("boom " + i, "ERROR", options));
        }
        return events;
    }

    private Transport.Result send(List<StacklightEvent> batch) {
        return new HttpTransport(options(), new Logger(false)).send(batch, Duration.ofSeconds(5));
    }

    @Test
    void aWholeBatchGoesInOneRequest() {
        // The point of the endpoint. Six events used to be six round trips.
        Transport.Result result = send(batch(6));

        assertThat(result.delivered()).isTrue();
        assertThat(result.accepted()).isEqualTo(6);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(sizes).containsExactly(6);
        assertThat(paths).containsExactly("/api/events/batch");
    }

    @Test
    void anEventTheCollectorCouldNotTakeYetComesBackToBeRequeued() {
        mark = index -> index == 2
                ? "\"stored\":false,\"error\":\"database unavailable\",\"retryable\":true"
                : null;

        Transport.Result result = send(batch(5));

        // The request worked. Only one event is still owed.
        assertThat(result.delivered()).isTrue();
        assertThat(result.accepted()).isEqualTo(4);
        assertThat(result.pending()).hasSize(1);
        assertThat(result.discarded()).isEmpty();
    }

    @Test
    void anEventTheCollectorRefusedOutrightIsDiscardedRatherThanRetried() {
        mark = index -> index == 1
                ? "\"stored\":false,\"error\":\"service must not be blank\",\"retryable\":false"
                : null;

        Transport.Result result = send(batch(3));

        assertThat(result.delivered()).isTrue();
        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.pending()).isEmpty();
        assertThat(result.discarded()).hasSize(1);
    }

    @Test
    void aDuplicateCountsAsAcceptedBecauseTheCollectorHasIt() {
        mark = index -> index == 0 ? "\"stored\":false,\"error\":null,\"retryable\":false" : null;

        Transport.Result result = send(batch(2));

        assertThat(result.accepted()).isEqualTo(2);
        assertThat(result.pending()).isEmpty();
        assertThat(result.discarded()).isEmpty();
    }

    @Test
    void aReceiptThatCannotBeReadIsTreatedAsAcceptedNotAsFailure() {
        // The collector answered 2xx, so the events are with it. Re-sending them because
        // this client could not parse the receipt would be worse than losing the receipt.
        rawBody = "not json";

        Transport.Result result = send(batch(3));

        assertThat(result.delivered()).isTrue();
        assertThat(result.accepted()).isEqualTo(3);
        assertThat(result.pending()).isEmpty();
    }

    @Test
    void aBadKeyIsNotWorthRetrying() {
        status = 401;

        Transport.Result result = send(batch(3));

        assertThat(result.retryable()).isFalse();
        assertThat(result.accepted()).isZero();
        assertThat(result.pending()).hasSize(3);
        assertThat(result.detail()).contains("401");
    }

    @Test
    void aBatchTheCollectorWillNotParseIsNotWorthRetrying() {
        status = 400;

        Transport.Result result = send(batch(2));

        assertThat(result.retryable()).isFalse();
        assertThat(result.detail()).contains("400");
    }

    @Test
    void aCollectorStillWakingUpIsWorthRetrying() {
        // 503 is what the platform returns while the collector is still starting, which is
        // the case this whole client exists to survive.
        status = 503;

        Transport.Result result = send(batch(2));

        assertThat(result.retryable()).isTrue();
        assertThat(result.accepted()).isZero();
        assertThat(result.pending()).hasSize(2);
    }

    @Test
    void anUnreachableCollectorIsWorthRetrying() throws Exception {
        StacklightOptions options = options();
        server.stop(0);

        Transport.Result result =
                new HttpTransport(options, new Logger(false))
                        .send(batch(1), Duration.ofSeconds(2));

        assertThat(result.delivered()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.pending()).hasSize(1);
    }
}
