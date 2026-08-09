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

/** A fix that did not hold has to be visible as such. */
@SpringBootTest
@Testcontainers
class RegressionDetectionTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String TRACE =
            "\tat com.example.checkout.CartService.total(CartService.java:42)\n";

    @Autowired private IngestService ingestService;
    @Autowired private GroupStatusStore statusStore;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
    }

    private static IngestRequest request(String release) {
        return new IngestRequest(
                null, "checkout-api", "ERROR", "boom", "java",
                "java.lang.NullPointerException", TRACE, release, null);
    }

    private String statusOf(long groupId) {
        return jdbc.sql("select status from event_groups where id = :id")
                .param("id", groupId)
                .query(String.class)
                .single();
    }

    @Test
    void aNewGroupStartsOpen() {
        IngestService.Result result = ingestService.ingest(UUID.randomUUID(), request("1.4.0"));

        assertThat(statusOf(result.groupId())).isEqualTo("open");
        assertThat(result.regressed()).isFalse();
    }

    @Test
    void anEventOnAResolvedGroupMarksItRegressed() {
        long groupId = ingestService.ingest(UUID.randomUUID(), request("1.4.0")).groupId();
        statusStore.updateStatus(groupId, "resolved", "1.5.0");

        IngestService.Result comeback =
                ingestService.ingest(UUID.randomUUID(), request("1.6.0"));

        assertThat(comeback.regressed()).isTrue();
        assertThat(statusOf(groupId)).isEqualTo("regressed");
    }

    @Test
    void theReleaseThatBroughtItBackIsRecordedNextToTheOneItWasFixedIn() {
        long groupId = ingestService.ingest(UUID.randomUUID(), request("1.4.0")).groupId();
        statusStore.updateStatus(groupId, "resolved", "1.5.0");

        ingestService.ingest(UUID.randomUUID(), request("1.6.0"));

        var row =
                jdbc.sql(
                                """
                                select resolved_in_release, regressed_in_release,
                                       release_first, release_last
                                  from event_groups where id = :id
                                """)
                        .param("id", groupId)
                        .query()
                        .singleRow();

        assertThat(row.get("resolved_in_release")).isEqualTo("1.5.0");
        assertThat(row.get("regressed_in_release")).isEqualTo("1.6.0");
        assertThat(row.get("release_first")).isEqualTo("1.4.0");
        assertThat(row.get("release_last")).isEqualTo("1.6.0");
    }

    @Test
    void anAlreadyRegressedGroupDoesNotKeepRestampingItself() {
        long groupId = ingestService.ingest(UUID.randomUUID(), request("1.4.0")).groupId();
        statusStore.updateStatus(groupId, "resolved", "1.5.0");
        ingestService.ingest(UUID.randomUUID(), request("1.6.0"));

        var firstStamp =
                jdbc.sql("select regressed_at from event_groups where id = :id")
                        .param("id", groupId)
                        .query(java.time.OffsetDateTime.class)
                        .single();

        IngestService.Result later = ingestService.ingest(UUID.randomUUID(), request("1.7.0"));

        // The moment it came back is a fact about the first comeback event, not the
        // most recent one.
        assertThat(later.regressed()).isFalse();
        assertThat(statusOf(groupId)).isEqualTo("regressed");
        assertThat(
                        jdbc.sql("select regressed_at from event_groups where id = :id")
                                .param("id", groupId)
                                .query(java.time.OffsetDateTime.class)
                                .single())
                .isEqualTo(firstStamp);
        assertThat(
                        jdbc.sql("select regressed_in_release from event_groups where id = :id")
                                .param("id", groupId)
                                .query(String.class)
                                .single())
                .isEqualTo("1.6.0");
    }

    @Test
    void anIgnoredGroupStaysIgnored() {
        // Ignored means "stop telling me about this", so new events must not undo it.
        long groupId = ingestService.ingest(UUID.randomUUID(), request("1.4.0")).groupId();
        statusStore.updateStatus(groupId, "ignored", null);

        ingestService.ingest(UUID.randomUUID(), request("1.6.0"));

        assertThat(statusOf(groupId)).isEqualTo("ignored");
    }

    @Test
    void reopeningByHandClearsTheRegressionMarks() {
        long groupId = ingestService.ingest(UUID.randomUUID(), request("1.4.0")).groupId();
        statusStore.updateStatus(groupId, "resolved", "1.5.0");
        ingestService.ingest(UUID.randomUUID(), request("1.6.0"));

        statusStore.updateStatus(groupId, "open", null);

        var row =
                jdbc.sql(
                                "select status, regressed_at, resolved_at from event_groups where id = :id")
                        .param("id", groupId)
                        .query()
                        .singleRow();

        assertThat(row.get("status")).isEqualTo("open");
        assertThat(row.get("regressed_at")).isNull();
        assertThat(row.get("resolved_at")).isNull();
    }

    @Test
    void resolvingWithoutNamingAReleaseFallsBackToTheLastOneSeen() {
        long groupId = ingestService.ingest(UUID.randomUUID(), request("1.4.0")).groupId();

        statusStore.updateStatus(groupId, "resolved", null);

        assertThat(
                        jdbc.sql("select resolved_in_release from event_groups where id = :id")
                                .param("id", groupId)
                                .query(String.class)
                                .single())
                .isEqualTo("1.4.0");
    }

    @Test
    void updatingAGroupThatDoesNotExistReportsSo() {
        assertThat(statusStore.updateStatus(999999L, "resolved", "1.0.0")).isFalse();
    }
}
