package dev.stacklight.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The triage console: its list endpoint, its writes, and the line between its key and the
 * ingest one.
 *
 * <p>Half of these assert a 401 that a reader might expect to be a 200. That is the point.
 * The ingest key is deployed to every installation reporting errors, so the tests that
 * matter most here are the ones proving it cannot be used to change a group's status, and
 * that the console key cannot be used to write events.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stacklight.ingest.api-key=" + GroupConsoleTests.INGEST_KEY,
            "stacklight.console.api-key=" + GroupConsoleTests.CONSOLE_KEY
        })
@Testcontainers
class GroupConsoleTests {

    static final String INGEST_KEY = "ingest-key";
    static final String CONSOLE_KEY = "console-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @Autowired private JdbcClient jdbc;

    private long groupId;

    @BeforeEach
    void seed() {
        // events references event_groups, so it goes first even though nothing here
        // writes one -- a later test that does would otherwise fail on the foreign key.
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();

        groupId =
                jdbc.sql(
                                """
                                insert into event_groups (
                                    fingerprint, fingerprint_version, service, platform, level,
                                    title, fingerprint_input, event_count, release_last)
                                values ('abc123', 1, 'checkout-api', 'java', 'error',
                                        'IllegalStateException: could not price the cart',
                                        'input', 7, '1.4.0')
                                returning id
                                """)
                        .query(Long.class)
                        .single();
    }

    private String statusOf(long id) {
        return jdbc.sql("select status from event_groups where id = :id")
                .param("id", id)
                .query(String.class)
                .single();
    }

    // ---- the list endpoint -------------------------------------------------------

    @Test
    void listingWithoutAKeyIsRejected() throws Exception {
        assertThat(get("/api/groups", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void listingWithTheIngestKeyIsRejected() throws Exception {
        HttpResponse<String> response =
                get("/api/groups", ApiKeyFilter.INGEST_HEADER, INGEST_KEY);

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void listingWithTheConsoleKeyReturnsTheGroups() throws Exception {
        HttpResponse<String> response =
                get("/api/groups", ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":" + groupId)
                .contains("\"service\":\"checkout-api\"")
                .contains("\"status\":\"open\"")
                .contains("\"eventCount\":7");
    }

    @Test
    void theListSerialisesLastSeenAsAnIsoStringRatherThanANumber() throws Exception {
        HttpResponse<String> response =
                get("/api/groups", ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY);

        // new Date(...) in the console reads a number as milliseconds since 1970, so a
        // serialisation setting turning this into an epoch would silently date every row
        // to 1970 rather than fail.
        assertThat(response.body()).containsPattern("\"lastSeen\":\"\\d{4}-\\d{2}-\\d{2}T");
    }

    @Test
    void anUnknownStatusFilterIsRejectedRatherThanIgnored() throws Exception {
        HttpResponse<String> response =
                get("/api/groups?status=banana", ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY);

        assertThat(response.statusCode()).isEqualTo(400);
    }

    // ---- the writes --------------------------------------------------------------

    @Test
    void theConsoleKeyMovesAGroupToResolved() throws Exception {
        HttpResponse<String> response =
                patch(groupId, "{\"status\":\"resolved\",\"release\":\"1.7.0\"}",
                        ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(statusOf(groupId)).isEqualTo("resolved");
    }

    @Test
    void theIngestKeyCannotChangeAGroupStatus() throws Exception {
        HttpResponse<String> response =
                patch(groupId, "{\"status\":\"resolved\"}",
                        ApiKeyFilter.INGEST_HEADER, INGEST_KEY);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(statusOf(groupId)).isEqualTo("open");
    }

    @Test
    void theConsoleKeyCannotWriteEvents() throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(base() + "/api/events"))
                        .header("Content-Type", "application/json")
                        .header(ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY)
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"service\":\"s\",\"level\":\"ERROR\",\"message\":\"m\"}"))
                        .build();

        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void anInvalidStatusIsRejected() throws Exception {
        HttpResponse<String> response =
                patch(groupId, "{\"status\":\"banana\"}",
                        ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(statusOf(groupId)).isEqualTo("open");
    }

    @Test
    void movingAGroupThatDoesNotExistIsANotFound() throws Exception {
        HttpResponse<String> response =
                patch(999999L, "{\"status\":\"resolved\"}",
                        ApiKeyFilter.CONSOLE_HEADER, CONSOLE_KEY);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // ---- the page ----------------------------------------------------------------

    @Test
    void theConsolePageIsServedWithoutAKeyBecauseItCarriesNoData() throws Exception {
        HttpResponse<String> response = get("/console.html", null, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Console key");
        // The shell is empty: no group, no service, no title reaches it from the server.
        assertThat(response.body()).doesNotContain("checkout-api");
    }

    @Test
    void theConsoleScriptNeverBuildsMarkupFromAString() throws Exception {
        String script =
                new String(
                        new ClassPathResource("static/console.js").getContentAsByteArray(),
                        StandardCharsets.UTF_8);

        // Comments are removed first, so the rule applies to what runs rather than to what
        // the file says about itself. Without this the script cannot explain the rule
        // without breaking it -- the first version of this test failed on its own subject's
        // header comment. The stripper is deliberately simple because the file it reads is
        // one this repository writes.
        String code = withoutComments(script);

        // Group titles come from error messages somebody else's application sent, so one of
        // these here would turn a reported error into script running in the operator's
        // browser -- in the tab holding the console key. The rule is that every value goes
        // in through textContent, and this is what stops it lapsing quietly.
        assertThat(code)
                .doesNotContain("innerHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("document.write");

        // A stripper that ate the whole file would make the assertions above vacuous.
        assertThat(code).contains("textContent");
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }

    // ---- plumbing ----------------------------------------------------------------

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path, String header, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (header != null) {
            request.header(header, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patch(long id, String json, String header, String key)
            throws Exception {

        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(base() + "/api/groups/" + id))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(json));
        if (header != null) {
            request.header(header, key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
