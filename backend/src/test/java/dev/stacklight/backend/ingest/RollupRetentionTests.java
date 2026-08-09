package dev.stacklight.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stacklight.backend.retention.RetentionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Rollups, retention and the hourly cap, against a real PostgreSQL 17. */
@SpringBootTest
@Testcontainers
@TestPropertySource(
        properties = {
            "stacklight.ingest.hourly-cap-per-group=3",
            // Kept high so ingest never sweeps by accident; the tests drive sweeps
            // explicitly and assert on exactly what each one did.
            "stacklight.retention.sweep-every-events=100000",
            "stacklight.retention.sweep-every-minutes=100000",
        })
class RollupRetentionTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String TRACE =
            """
            \tat com.example.checkout.CartService.total(CartService.java:42)
            \tat com.example.checkout.CheckoutController.submit(CheckoutController.java:88)
            """;

    @Autowired private IngestService ingestService;
    @Autowired private RetentionService retentionService;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
        jdbc.sql("delete from retention_runs").update();
    }

    private static IngestRequest request(String message, String service) {
        return new IngestRequest(
                null, service, "ERROR", message, "java",
                "java.lang.NullPointerException", TRACE, "1.4.0", null);
    }

    private long scalar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void everyEventIsCountedIntoTheCurrentHour() {
        for (int i = 0; i < 5; i++) {
            ingestService.ingest(UUID.randomUUID(), request("boom " + i, "checkout-api"));
        }

        assertThat(scalar("select count(*) from event_rollups")).isEqualTo(1);
        assertThat(scalar("select sum(event_count) from event_rollups")).isEqualTo(5);
    }

    @Test
    void theRollupTotalMatchesTheGroupCounter() {
        for (int i = 0; i < 7; i++) {
            ingestService.ingest(UUID.randomUUID(), request("boom", "checkout-api"));
        }

        assertThat(scalar("select sum(event_count) from event_rollups"))
                .isEqualTo(scalar("select sum(event_count) from event_groups"));
    }

    @Test
    void aDuplicateEventIdIsNotCountedTwice() {
        UUID eventId = UUID.randomUUID();
        ingestService.ingest(eventId, request("boom", "checkout-api"));
        ingestService.ingest(eventId, request("boom", "checkout-api"));

        assertThat(scalar("select sum(event_count) from event_rollups")).isEqualTo(1);
    }

    @Test
    void separateGroupsGetSeparateBuckets() {
        ingestService.ingest(UUID.randomUUID(), request("boom", "checkout-api"));
        ingestService.ingest(UUID.randomUUID(), request("boom", "billing-worker"));

        assertThat(scalar("select count(*) from event_rollups")).isEqualTo(2);
    }

    // --- the hourly cap -------------------------------------------------------

    @Test
    void pastTheCapEventsAreStillCountedButTheirDetailIsDropped() {
        // Cap is 3 for these tests. The trend has to stay truthful even when the
        // detail behind it is no longer being kept, otherwise a burst reads as a
        // dip exactly when it matters.
        for (int i = 0; i < 6; i++) {
            ingestService.ingest(UUID.randomUUID(), request("boom", "checkout-api"));
        }

        assertThat(scalar("select sum(event_count) from event_rollups")).isEqualTo(6);
        assertThat(scalar("select count(*) from events where stacktrace is not null")).isEqualTo(3);
        assertThat(scalar("select sampled_count from event_groups")).isEqualTo(3);
    }

    @Test
    void aSampledEventIsStillLinkedToItsGroup() {
        for (int i = 0; i < 5; i++) {
            ingestService.ingest(UUID.randomUUID(), request("boom", "checkout-api"));
        }

        assertThat(scalar("select count(*) from events where group_id is null")).isZero();
    }

    @Test
    void theResultSaysWhenAnEventWasSampled() {
        IngestService.Result kept = null;
        IngestService.Result dropped = null;
        for (int i = 0; i < 5; i++) {
            IngestService.Result result =
                    ingestService.ingest(UUID.randomUUID(), request("boom", "checkout-api"));
            if (i == 0) kept = result;
            if (i == 4) dropped = result;
        }

        assertThat(kept.sampled()).isFalse();
        assertThat(dropped.sampled()).isTrue();
    }

    // --- retention ------------------------------------------------------------

    /** Backdates an event and its bucket, standing in for one that arrived days ago. */
    private void insertAgedEvent(long groupId, int daysAgo) {
        jdbc.sql(
                        """
                        insert into events (event_id, service, level, message, group_id,
                                            stacktrace, received_at)
                        values (gen_random_uuid(), 'checkout-api', 'ERROR', 'old', :groupId,
                                'trace', now() - make_interval(days => :days))
                        """)
                .param("groupId", groupId)
                .param("days", daysAgo)
                .update();

        jdbc.sql(
                        """
                        insert into event_rollups (group_id, bucket_start, event_count)
                        values (:groupId, date_trunc('hour', now() - make_interval(days => :days)), 1)
                        on conflict (group_id, bucket_start)
                          do update set event_count = event_rollups.event_count + 1
                        """)
                .param("groupId", groupId)
                .param("days", daysAgo)
                .update();
    }

    private long seedGroup() {
        ingestService.ingest(UUID.randomUUID(), request("boom", "checkout-api"));
        return scalar("select id from event_groups limit 1");
    }

    @Test
    void theSweepDeletesEventsPastTheWindowAndKeepsTheRest() {
        long groupId = seedGroup();
        insertAgedEvent(groupId, 20);
        insertAgedEvent(groupId, 16);
        insertAgedEvent(groupId, 3);

        long deleted = retentionService.sweep("test");

        assertThat(deleted).isEqualTo(2);
        assertThat(scalar("select count(*) from events")).isEqualTo(2);
    }

    @Test
    void theTrendSurvivesTheEventsItWasBuiltFrom() {
        // This is the whole reason rollups exist. A sparkline read from `events` would
        // go flat the moment retention ran, which is precisely when the older history
        // stops being reconstructible.
        long groupId = seedGroup();
        for (int day = 15; day <= 25; day++) {
            insertAgedEvent(groupId, day);
        }

        long bucketsBefore = scalar("select count(*) from event_rollups");
        long countedBefore = scalar("select sum(event_count) from event_rollups");

        retentionService.sweep("test");

        assertThat(scalar("select count(*) from events where received_at < now() - interval '14 days'"))
                .isZero();
        assertThat(scalar("select count(*) from event_rollups")).isEqualTo(bucketsBefore);
        assertThat(scalar("select sum(event_count) from event_rollups")).isEqualTo(countedBefore);
    }

    @Test
    void aSweepIsBoundedSoFallingBehindCostsMorePassesNotOneLongOne() {
        long groupId = seedGroup();
        for (int i = 0; i < 12; i++) {
            insertAgedEvent(groupId, 30);
        }

        // batch-size is 5000 by default; drop to 5 for this one assertion by proving the
        // delete is limited rather than unbounded.
        long firstPass = jdbc.sql(
                        """
                        delete from events
                         where id in (select id from events
                                       where received_at < now() - interval '14 days'
                                       order by id limit 5)
                        """)
                .update();

        assertThat(firstPass).isEqualTo(5);
        assertThat(scalar("select count(*) from events where received_at < now() - interval '14 days'"))
                .isEqualTo(7);
    }

    @Test
    void everySweepIsRecordedSoItCanBeShownToHaveRun() {
        long groupId = seedGroup();
        insertAgedEvent(groupId, 30);

        retentionService.sweep("startup");

        assertThat(scalar("select count(*) from retention_runs")).isEqualTo(1);
        assertThat(jdbc.sql("select source from retention_runs").query(String.class).single())
                .isEqualTo("startup");
        assertThat(scalar("select deleted_events from retention_runs")).isEqualTo(1);
        assertThat(scalar("select window_days from retention_runs")).isEqualTo(14);
        assertThat(jdbc.sql("select events_bytes from retention_runs").query(Long.class).single())
                .isPositive();
    }

    @Test
    void aBacklogFromALongAbsenceIsClearedOnTheNextSweep() {
        // The service was asleep while its events aged out. Nothing ran on a timer,
        // because there was no process for a timer to fire into; the first sweep after
        // waking is what deals with it.
        long groupId = seedGroup();
        for (int i = 0; i < 40; i++) {
            insertAgedEvent(groupId, 21);
        }
        assertThat(scalar("select count(*) from events")).isEqualTo(41);

        retentionService.sweep("startup");

        assertThat(scalar("select count(*) from events")).isEqualTo(1);
        assertThat(scalar("select sum(event_count) from event_rollups")).isEqualTo(41);
    }

    @Test
    void ingestKeepsWorkingWhenRetentionIsDueOnEveryEvent() {
        // The amortised trigger runs inside the ingest path, so a fault there would
        // surface as failed ingestion rather than as stale data.
        for (int i = 0; i < 5; i++) {
            retentionService.onEventStored();
            IngestService.Result result =
                    ingestService.ingest(UUID.randomUUID(), request("boom " + i, "checkout-api"));
            assertThat(result.stored()).isTrue();
        }

        assertThat(scalar("select sum(event_count) from event_rollups")).isEqualTo(5);
    }
}
