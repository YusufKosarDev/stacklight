package dev.stacklight.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The new-group rule, driven through the ingest path rather than by calling the detector
 * directly.
 *
 * <p>Written because for a while the rule was reachable only from a test. Every piece of
 * it existed and was covered — the threshold check, the alert kind, its own wording in the
 * email, its column on the dashboard — but nothing on the ingest path ever called it, so
 * no alert of this kind could be raised in production. Tests that call the detector
 * themselves cannot see that; one that goes through {@link IngestService} cannot miss it.
 */
@SpringBootTest
@Testcontainers
class NewGroupAlertingTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String TRACE =
            "\tat com.example.checkout.CartService.total(CartService.java:42)\n";

    /** {@code stacklight.detection.new-group-threshold} */
    private static final int THRESHOLD = 10;

    @Autowired private IngestService ingestService;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from events").update();
        // alerts and detector_observations both cascade from the group.
        jdbc.sql("delete from event_groups").update();
    }

    private static IngestRequest request() {
        return new IngestRequest(
                null, "checkout-api", "ERROR", "boom", "java",
                "java.lang.NullPointerException", TRACE, "1.4.0", null);
    }

    /** Sends {@code count} events of the same fault, so they all land in one group. */
    private long send(int count) {
        long groupId = 0;
        for (int i = 0; i < count; i++) {
            groupId = ingestService.ingest(UUID.randomUUID(), request()).groupId();
        }
        return groupId;
    }

    private long countAlerts() {
        return jdbc.sql("select count(*) from alerts").query(Long.class).single();
    }

    @Test
    void aNewGroupReachingTheThresholdIsAlertedThroughTheIngestPath() {
        long groupId = send(THRESHOLD);

        var row =
                jdbc.sql("select kind, group_id from alerts").query().singleRow();

        assertThat(row.get("kind")).isEqualTo("new_group");
        assertThat(row.get("group_id")).isEqualTo(groupId);
    }

    @Test
    void aNewGroupBelowTheThresholdIsNotAlerted() {
        send(THRESHOLD - 1);

        assertThat(countAlerts()).isZero();
    }

    @Test
    void theRestOfTheBurstDoesNotKeepAlerting() {
        // The events that make a group worth mentioning keep arriving after it has been
        // mentioned. One alert per group per cooldown, or the feature teaches people to
        // filter it away.
        send(THRESHOLD * 3);

        assertThat(countAlerts()).isEqualTo(1);
    }

    @Test
    void theStatisticalDetectorsStayOutOfIt() {
        // A group inside the new-group window cannot have min-history-hours of history,
        // so no detector may fire on it and the two rules cannot both raise an alert.
        // This is what lets the new-group call sit unguarded on the ingest path.
        send(THRESHOLD * 3);

        assertThat(
                        jdbc.sql("select count(*) from detector_observations where fired")
                                .query(Long.class)
                                .single())
                .isZero();
    }
}
