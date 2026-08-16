package dev.stacklight.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the write path publishes about itself, and who is allowed to read it.
 *
 * <p>Two of these assert an absence rather than a presence -- that the metrics endpoint is
 * not public, and that no series is tagged with anything a caller chose. Both are the sort
 * of thing that is true on the day it is written and quietly stops being true later.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stacklight.ingest.api-key=" + ObservabilityTests.INGEST_KEY,
            "stacklight.console.api-key=" + ObservabilityTests.CONSOLE_KEY
        })
@Testcontainers
class ObservabilityTests {

    static final String INGEST_KEY = "ingest-key";
    static final String CONSOLE_KEY = "console-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @Autowired private Environment environment;

    @Autowired private ApplicationContext context;

    // ---- who may read the metrics -------------------------------------------------

    @Test
    void theMetricsEndpointIsNotPublic() throws Exception {
        assertThat(get("/actuator/prometheus", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void theIngestKeyDoesNotOpenTheMetricsEndpoint() throws Exception {
        HttpResponse<String> response =
                get("/actuator/prometheus", "X-Stacklight-Key", INGEST_KEY);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void theConsoleKeyReadsTheMetrics() throws Exception {
        HttpResponse<String> response =
                get("/actuator/prometheus", "X-Stacklight-Console-Key", CONSOLE_KEY);

        assertThat(response.statusCode())
                .withFailMessage(
                        "expected 200 but got %s. prometheus-related beans: %s",
                        response.statusCode(),
                        java.util.Arrays.stream(context.getBeanDefinitionNames())
                                .filter(n -> n.toLowerCase().contains("prometheus"))
                                .collect(java.util.stream.Collectors.joining(", ")))
                .isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void healthStaysOpenBecauseItIsTheUptimeTarget() throws Exception {
        assertThat(get("/actuator/health", null, null).statusCode()).isEqualTo(200);
    }

    // ---- what it publishes ---------------------------------------------------------

    @Test
    void ingestingAnEventPublishesTheTimersItIsMeasuredBy() throws Exception {
        post(event(), INGEST_KEY);

        String metrics = scrape();

        assertThat(metrics).contains("stacklight_ingest_seconds_count");
        assertThat(metrics).contains("stacklight_grouping_seconds_count");
        assertThat(metrics).contains("outcome=\"stored\"");
    }

    @Test
    void everyMeterCarriesTheServiceItCameFrom() throws Exception {
        post(event(), INGEST_KEY);

        assertThat(scrape()).contains("service=\"stacklight-backend\"");
    }

    @Test
    void aDuplicateIsCountedSeparatelyFromAStore() throws Exception {
        String body = event();
        post(body, INGEST_KEY);
        post(body, INGEST_KEY);

        assertThat(scrape()).contains("outcome=\"duplicate\"");
    }

    @Test
    void noSeriesIsTaggedWithSomethingTheCallerChose() throws Exception {
        // The service name below arrives in the request body. If it reached a tag, anyone
        // holding an ingest key could mint series until the registry filled the heap.
        post(event("a-service-name-nobody-should-be-able-to-mint"), INGEST_KEY);

        String metrics = scrape();

        assertThat(metrics).doesNotContain("a-service-name-nobody-should-be-able-to-mint");
    }

    // ---- the correlation id ---------------------------------------------------------

    @Test
    void everyRequestComesBackWithAnIdToQuoteBack() throws Exception {
        HttpResponse<String> response = get("/api/groups", null, null);

        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
    }

    @Test
    void anIdTheCallerSuppliedIsKept() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/groups"))
                        .header("X-Request-Id", "client-supplied-42")
                        .GET()
                        .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("client-supplied-42");
    }

    @Test
    void anIdThatCouldForgeALogLineIsReplacedRatherThanEchoed() throws Exception {
        // A newline in a value that gets written to the log is a whole fabricated entry.
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/groups"))
                        .header("X-Request-Id", "abc def")
                        .GET()
                        .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-Id"))
                .isPresent()
                .get()
                .isNotEqualTo("abc def");
    }

    @Test
    void anIdTooLongToBeOneIsReplaced() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/groups"))
                        .header("X-Request-Id", "x".repeat(200))
                        .GET()
                        .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-Id").orElseThrow().length())
                .isLessThanOrEqualTo(64);
    }

    // ---- the logging default ---------------------------------------------------------

    @Test
    void structuredLoggingIsOffUnlessSomethingIsThereToReadIt() {
        // Render's own viewer filters by level and instance and does not parse arbitrary
        // JSON fields, so defaulting to JSON would trade a readable log for an unreadable
        // one. LOGGING_STRUCTURED_FORMAT_CONSOLE turns it on where there is a pipeline --
        // and the property is absent rather than blank, because it has no value meaning off.
        assertThat(environment.getProperty("logging.structured.format.console")).isNull();
    }

    // ---- plumbing ---------------------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
    }

    private String scrape() throws Exception {
        return get("/actuator/prometheus", "X-Stacklight-Console-Key", CONSOLE_KEY).body();
    }

    private String event() {
        return event("checkout-api");
    }

    private String event(String service) {
        return """
               {"eventId":"%s","service":"%s","level":"ERROR","message":"boom"}
               """
                .formatted(UUID.randomUUID(), service);
    }

    private HttpResponse<String> get(String path, String header, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (header != null) {
            request.header(header, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void post(String json, String key) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/events"))
                        .header("Content-Type", "application/json")
                        .header("X-Stacklight-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

        http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
