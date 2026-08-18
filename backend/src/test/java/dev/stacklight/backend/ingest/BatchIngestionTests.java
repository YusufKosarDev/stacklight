package dev.stacklight.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A queue drain in one request.
 *
 * <p>The tests that matter most here are about what a batch does <em>not</em> do to itself:
 * one invalid event does not discard the valid ones around it, one duplicate does not stop
 * the rest, and a batch cannot get past the per-group hourly cap that a stream of single
 * requests obeys.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stacklight.ingest.api-key=" + BatchIngestionTests.API_KEY,
            // Small enough to cross inside one batch, so the cap is tested rather than
            // assumed. Production runs at 200.
            "stacklight.ingest.hourly-cap-per-group=5"
        })
@Testcontainers
class BatchIngestionTests {

    static final String API_KEY = "test-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from alerts").update();
        jdbc.sql("delete from detector_observations").update();
        jdbc.sql("delete from event_rollups").update();
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
    }

    // ---- the shape of the answer ---------------------------------------------------

    @Test
    void aBatchIsAcceptedAndReportsEveryEvent() throws Exception {
        HttpResponse<String> response = post(batch(3));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body())
                .contains("\"accepted\":3")
                .contains("\"stored\":3")
                .contains("\"failed\":0");

        assertThat(count("events")).isEqualTo(3);
    }

    @Test
    void theResultCarriesTheGroupAndFingerprintForEachEvent() throws Exception {
        HttpResponse<String> response = post(batch(2));

        assertThat(response.body()).containsPattern("\"fingerprint\":\"[0-9a-f]{32}\"");
        assertThat(response.body()).containsPattern("\"groupId\":\\d+");
    }

    // ---- one bad event costs only itself --------------------------------------------

    @Test
    void anInvalidEventIsReportedWithoutDiscardingTheRest() throws Exception {
        String body =
                "["
                        + event(UUID.randomUUID(), "checkout-api", "boom")
                        + ","
                        // Blank service: fails @NotBlank, and no retry can fix it.
                        + event(UUID.randomUUID(), "", "boom")
                        + ","
                        + event(UUID.randomUUID(), "checkout-api", "boom again")
                        + "]";

        HttpResponse<String> response = post(body);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"accepted\":3").contains("\"stored\":2");
        assertThat(response.body()).contains("\"retryable\":false");
        assertThat(count("events")).isEqualTo(2);
    }

    @Test
    void aValidationFailureNamesTheFieldItRejected() throws Exception {
        HttpResponse<String> response =
                post("[" + event(UUID.randomUUID(), "", "boom") + "]");

        assertThat(response.body()).contains("service");
    }

    // ---- idempotency ------------------------------------------------------------------

    @Test
    void anEventIdAlreadySeenIsADuplicateRatherThanAFailure() throws Exception {
        UUID repeated = UUID.randomUUID();
        String body = "[" + event(repeated, "checkout-api", "boom") + "]";

        post(body);
        HttpResponse<String> second = post(body);

        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.body()).contains("\"duplicates\":1").contains("\"failed\":0");
        assertThat(count("events")).isEqualTo(1);
    }

    @Test
    void resendingAWholeBatchCannotInflateTheCounters() throws Exception {
        String body = batch(4);

        post(body);
        post(body);

        assertThat(count("events")).isEqualTo(4);
        Long counted =
                jdbc.sql("select coalesce(sum(event_count), 0) from event_groups")
                        .query(Long.class)
                        .single();
        assertThat(counted).isEqualTo(4);
    }

    // ---- the envelope -------------------------------------------------------------------

    @Test
    void anEmptyBatchIsRejected() throws Exception {
        assertThat(post("[]").statusCode()).isEqualTo(400);
    }

    @Test
    void aBatchOverTheLimitIsRejectedWholesale() throws Exception {
        HttpResponse<String> response = post(batch(BatchIngestService.MAX_EVENTS + 1));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(count("events")).isZero();
    }

    @Test
    void aBatchExactlyAtTheLimitIsAccepted() throws Exception {
        HttpResponse<String> response = post(batch(BatchIngestService.MAX_EVENTS));

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(count("events")).isEqualTo(BatchIngestService.MAX_EVENTS);
    }

    @Test
    void theBatchEndpointNeedsTheIngestKeyLikeEverythingElse() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/events/batch"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(batch(1)))
                        .build();

        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    // ---- the cap still holds inside a batch ---------------------------------------------

    @Test
    void aBatchCannotGetPastThePerGroupHourlyCap() throws Exception {
        // Eight events of one fault against a cap of five: the trend must count all eight
        // while only five keep their detail. A batch that bypassed this would make the
        // cap a property of how a client chose to send rather than of the group.
        post(batch(8));

        Long sampled =
                jdbc.sql("select sampled_count from event_groups").query(Long.class).single();
        Long counted =
                jdbc.sql("select event_count from event_groups").query(Long.class).single();

        assertThat(counted).isEqualTo(8);
        assertThat(sampled).isGreaterThan(0);
    }

    @Test
    void eventsOverTheCapAreReportedAsSampledRatherThanLost() throws Exception {
        HttpResponse<String> response = post(batch(8));

        assertThat(response.body()).contains("\"sampled\":true");
        assertThat(response.body()).contains("\"stored\":8");
    }

    // ---- the single endpoint is untouched -------------------------------------------------

    @Test
    void theSingleEventEndpointStillWorksForClientsThatHaveNotMoved() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/events"))
                        .header("Content-Type", "application/json")
                        .header(ApiKeyFilter.INGEST_HEADER, API_KEY)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        event(UUID.randomUUID(), "checkout-api", "boom")))
                        .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(count("events")).isEqualTo(1);
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    /** N events of the same fault, so they land in one group. */
    private String batch(int size) {
        return IntStream.range(0, size)
                .mapToObj(i -> event(UUID.randomUUID(), "checkout-api", "could not price the cart"))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String event(UUID id, String service, String message) {
        return """
               {"eventId":"%s","service":"%s","level":"ERROR","message":"%s",
                "platform":"java","exceptionType":"java.lang.IllegalStateException",
                "stacktrace":"java.lang.IllegalStateException: %s\\n\\tat com.example.Cart.total(Cart.java:42)"}
               """
                .formatted(id, service, message, message);
    }

    private HttpResponse<String> post(String json) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/events/batch"))
                        .header("Content-Type", "application/json")
                        .header(ApiKeyFilter.INGEST_HEADER, API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
