package dev.stacklight.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The transport against a real server, using the one the JDK already ships so the client
 * keeps its promise of dragging in nothing.
 *
 * <p>What is worth testing here is the two judgements this class makes: which status codes
 * are worth another attempt, and — because the wire format is one event per request — how
 * far through a batch it got before it stopped.
 */
class HttpTransportTests {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    /** Requests up to this many are accepted; the rest answer with {@link #thenStatus}. */
    private volatile int acceptFirst = Integer.MAX_VALUE;

    private volatile int thenStatus = 503;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/events",
                exchange -> {
                    int seen = requests.incrementAndGet();
                    exchange.sendResponseHeaders(seen <= acceptFirst ? 202 : thenStatus, -1);
                    exchange.close();
                });
        server.start();
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
        return new HttpTransport(options(), new Logger(false))
                .send(batch, Duration.ofSeconds(5));
    }

    @Test
    void aBatchThatAllLandsIsDelivered() {
        Transport.Result result = send(batch(4));

        assertThat(result.delivered()).isTrue();
        assertThat(requests.get()).isEqualTo(4);
    }

    @Test
    void reportsHowFarItGotWhenTheCollectorStopsPartWayThrough() {
        // The case this field exists for: three events are with the collector, the fourth
        // is not, and the dispatcher must only owe the remainder.
        acceptFirst = 3;

        Transport.Result result = send(batch(6));

        assertThat(result.delivered()).isFalse();
        assertThat(result.retryable()).isTrue();
        assertThat(result.deliveredBeforeFailure()).isEqualTo(3);
        // It stopped at the first refusal rather than pushing the rest at a collector
        // that has just said it cannot take them.
        assertThat(requests.get()).isEqualTo(4);
    }

    @Test
    void aBadKeyIsNotWorthRetrying() {
        acceptFirst = 0;
        thenStatus = 401;

        Transport.Result result = send(batch(3));

        assertThat(result.delivered()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.deliveredBeforeFailure()).isZero();
        assertThat(result.detail()).contains("401");
    }

    @Test
    void aCollectorStillWakingUpIsWorthRetrying() {
        // 503 is what the platform returns while the collector is still starting, which
        // is the case this whole client exists to survive.
        acceptFirst = 0;
        thenStatus = 503;

        Transport.Result result = send(batch(2));

        assertThat(result.retryable()).isTrue();
        assertThat(result.deliveredBeforeFailure()).isZero();
    }

    @Test
    void anUnreachableCollectorIsWorthRetrying() throws Exception {
        server.stop(0);

        Transport.Result result = send(batch(1));

        assertThat(result.delivered()).isFalse();
        assertThat(result.retryable()).isTrue();
    }
}
