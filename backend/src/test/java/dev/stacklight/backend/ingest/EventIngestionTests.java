package dev.stacklight.backend.ingest;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the backend half of the step 0 slice against a real PostgreSQL 17:
 * migration applies, guard rejects unkeyed calls, and a POST lands as a row.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stacklight.ingest.api-key=" + EventIngestionTests.API_KEY)
@Testcontainers
class EventIngestionTests {

    static final String API_KEY = "test-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @Autowired private JdbcClient jdbc;

    @Test
    void migrationCreatesEventsTable() {
        Long count = jdbc.sql("select count(*) from events").query(Long.class).single();

        assertThat(count).isNotNull();
    }

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        HttpResponse<String> response = post(body(UUID.randomUUID()), null);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsRequestWithWrongApiKey() throws Exception {
        HttpResponse<String> response = post(body(UUID.randomUUID()), "not-the-key");

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsInvalidBody() throws Exception {
        String missingMessage =
                """
                {"service":"checkout-api","level":"ERROR","message":""}
                """;

        HttpResponse<String> response = post(missingMessage, API_KEY);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    @Test
    void storesEventAndIgnoresRepeatedEventId() throws Exception {
        UUID eventId = UUID.randomUUID();

        HttpResponse<String> first = post(body(eventId), API_KEY);
        HttpResponse<String> repeat = post(body(eventId), API_KEY);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(first.body()).contains("\"stored\":true");
        assertThat(repeat.statusCode()).isEqualTo(202);
        assertThat(repeat.body()).contains("\"stored\":false");

        Long stored =
                jdbc.sql("select count(*) from events where event_id = :id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single();
        assertThat(stored).isEqualTo(1L);

        String payloadService =
                jdbc.sql("select payload ->> 'release' from events where event_id = :id")
                        .param("id", eventId)
                        .query(String.class)
                        .single();
        assertThat(payloadService).isEqualTo("1.4.0");
    }

    @Test
    void storesEventWithoutPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        String withoutPayload =
                """
                {"eventId":"%s","service":"worker","level":"WARN","message":"queue lag"}
                """
                        .formatted(eventId);

        HttpResponse<String> response = post(withoutPayload, API_KEY);

        assertThat(response.statusCode()).isEqualTo(202);
        Long stored =
                jdbc.sql("select count(*) from events where event_id = :id and payload is null")
                        .param("id", eventId)
                        .query(Long.class)
                        .single();
        assertThat(stored).isEqualTo(1L);
    }

    private static String body(UUID eventId) {
        return """
               {"eventId":"%s",
                "service":"checkout-api",
                "level":"ERROR",
                "message":"NullPointerException in CartService.total",
                "payload":{"release":"1.4.0","stacktrace":["CartService.total(CartService.java:42)"]}}
               """
                .formatted(eventId);
    }

    private HttpResponse<String> post(String json, String apiKey) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null) {
            request.header(IngestAuthFilter.HEADER, apiKey);
        }

        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
