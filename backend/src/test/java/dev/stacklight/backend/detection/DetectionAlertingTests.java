package dev.stacklight.backend.detection;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stacklight.backend.alerting.AlertService;
import dev.stacklight.backend.alerting.AlertStore;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Shadow mode, alert raising and the outbox, against a real PostgreSQL 17. */
@SpringBootTest
@Testcontainers
class DetectionAlertingTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private DetectionService detectionService;
    @Autowired private AlertService alertService;
    @Autowired private AlertStore alertStore;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from alerts").update();
        jdbc.sql("delete from detector_observations").update();
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
    }

    /** A group that has existed for a day, with the given counts in its recent hours. */
    private long seedGroup(String fingerprint, List<Integer> hourlyHistory) {
        long groupId =
                jdbc.sql(
                                """
                                insert into event_groups (fingerprint, fingerprint_version, service,
                                    platform, level, title, fingerprint_input, first_seen, last_seen,
                                    event_count)
                                values (:fp, 1, 'checkout-api', 'java', 'ERROR', 'NullPointerException: boom',
                                        'v1', now() - interval '25 hours', now(), 0)
                                returning id
                                """)
                        .param("fp", fingerprint)
                        .query(Long.class)
                        .single();

        int hoursAgo = hourlyHistory.size();
        for (int count : hourlyHistory) {
            if (count > 0) {
                jdbc.sql(
                                """
                                insert into event_rollups (group_id, bucket_start, event_count)
                                values (:id, date_trunc('hour', now()) - make_interval(hours => :ago), :n)
                                """)
                        .param("id", groupId)
                        .param("ago", hoursAgo)
                        .param("n", count)
                        .update();
            }
            hoursAgo--;
        }
        return groupId;
    }

    private long scalar(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    // --- shadow mode ----------------------------------------------------------

    @Test
    void everyDetectorIsRecordedForTheBucketNotJustTheActiveOne() {
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 40);

        List<String> recorded =
                jdbc.sql("select detector from detector_observations order by detector")
                        .query(String.class)
                        .list();

        assertThat(recorded).containsExactly("ewma", "poisson", "zscore");
    }

    @Test
    void exactlyOneDetectorIsMarkedActive() {
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 40);

        assertThat(scalar("select count(*) from detector_observations where is_active")).isEqualTo(1);
        assertThat(
                        jdbc.sql("select detector from detector_observations where is_active")
                                .query(String.class)
                                .single())
                .isEqualTo(detectionService.activeName());
    }

    @Test
    void shadowDetectorsNeverRaiseAlerts() {
        // Sixty errors against a flat history of fifty. The z-score calls it ten sigma,
        // because a perfectly steady history collapses its denominator onto the floor and
        // the floor then does all the work. Poisson, which takes the spread from the rate,
        // puts the same hour at roughly one in eleven and says nothing. The disagreement
        // is recorded; only the active detector may act on it.
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 50));

        detectionService.evaluate(groupId, 60);

        List<String> fired =
                jdbc.sql("select detector from detector_observations where fired order by detector")
                        .query(String.class)
                        .list();

        assertThat(fired).containsExactly("zscore");
        assertThat(scalar("select count(*) from detector_observations where fired and is_active"))
                .isZero();
        assertThat(scalar("select count(*) from alerts")).isZero();
    }

    @Test
    void aQuietBucketIsNotEvaluatedAtAll() {
        // Below the count floor no detector can fire, so the query and the three
        // evaluations are skipped rather than performed and discarded.
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 2);

        assertThat(scalar("select count(*) from detector_observations")).isZero();
    }

    @Test
    void reevaluatingTheSameHourUpdatesTheVerdictRatherThanAppending() {
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 6);
        detectionService.evaluate(groupId, 40);

        assertThat(scalar("select count(*) from detector_observations")).isEqualTo(3);
        assertThat(scalar("select observed from detector_observations where detector = 'poisson'"))
                .isEqualTo(40);
    }

    // --- alerts ---------------------------------------------------------------

    @Test
    void theActiveDetectorFiringRaisesAnAlert() {
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 40);

        assertThat(scalar("select count(*) from alerts where kind = 'spike'")).isEqualTo(1);
        assertThat(jdbc.sql("select detector from alerts").query(String.class).single())
                .isEqualTo(detectionService.activeName());
    }

    @Test
    void aGroupIsNotAlertedTwiceWithinTheCooldown() {
        // A group in the middle of a spike produces events continuously. One alert per
        // event is how a mailbox teaches somebody to filter the whole feature away.
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 40);
        detectionService.evaluate(groupId, 80);
        detectionService.evaluate(groupId, 120);

        assertThat(scalar("select count(*) from alerts")).isEqualTo(1);
    }

    @Test
    void differentGroupsAreNotSuppressedByEachOthersCooldown() {
        long first = seedGroup("aaa", java.util.Collections.nCopies(24, 5));
        long second = seedGroup("bbb", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(first, 40);
        detectionService.evaluate(second, 40);

        assertThat(scalar("select count(*) from alerts")).isEqualTo(2);
    }

    @Test
    void theSpikeCooldownIsStillAnHourAfterSilenceWasGivenItsOwn() {
        // Silence holds for a day because a sweep only asks every three hours. The kinds
        // raised by an event arriving must not inherit that: a spike two hours after the
        // last one is a new burst and is still worth saying. Without this, wiring the
        // per-kind cooldown to the wrong value would go unnoticed -- every other cooldown
        // test passes just as well at a day as at an hour.
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 40);
        assertThat(scalar("select count(*) from alerts where kind = 'spike'")).isEqualTo(1);

        jdbc.sql("update alerts set created_at = created_at - interval '2 hours'").update();
        detectionService.evaluate(groupId, 80);

        assertThat(scalar("select count(*) from alerts where kind = 'spike'")).isEqualTo(2);
    }

    @Test
    void aRegressionRaisesItsOwnKindOfAlert() {
        long groupId = seedGroup("aaa", List.of());

        alertService.raiseRegression(groupId);

        assertThat(jdbc.sql("select kind from alerts").query(String.class).single())
                .isEqualTo("regression");
    }

    @Test
    void aNewGroupProducingVolumeIsWorthSaying() {
        long groupId = seedGroup("aaa", List.of());

        detectionService.evaluateNewGroup(groupId, 1, 25);

        assertThat(jdbc.sql("select kind from alerts").query(String.class).single())
                .isEqualTo("new_group");
    }

    @Test
    void aNewGroupBelowTheThresholdIsNot() {
        long groupId = seedGroup("aaa", List.of());

        detectionService.evaluateNewGroup(groupId, 1, 3);

        assertThat(scalar("select count(*) from alerts")).isZero();
    }

    // --- the outbox -----------------------------------------------------------

    @Test
    void withoutMailConfiguredAlertsAreRecordedAsDisabledRatherThanQueued() {
        // No mail settings in the test profile. Detection still works and the record is
        // still made; it simply is not queued, so a deployment that later configures
        // mail does not fire a backlog of everything it ever missed.
        long groupId = seedGroup("aaa", java.util.Collections.nCopies(24, 5));

        detectionService.evaluate(groupId, 40);

        assertThat(jdbc.sql("select delivery_state from alerts").query(String.class).single())
                .isEqualTo("disabled");
        assertThat(alertStore.pending(10)).isEmpty();
    }

    @Test
    void aFailedDeliveryIsRetriedUntilItRunsOutOfAttempts() {
        long groupId = seedGroup("aaa", List.of());
        long alertId =
                alertStore.raise(groupId, "spike", java.util.Optional.empty(), "boom", "pending");

        alertStore.markFailed(alertId, "connection refused", 3);
        assertThat(deliveryState(alertId)).isEqualTo("pending");

        alertStore.markFailed(alertId, "connection refused", 3);
        assertThat(deliveryState(alertId)).isEqualTo("pending");

        alertStore.markFailed(alertId, "connection refused", 3);
        assertThat(deliveryState(alertId)).isEqualTo("failed");

        // The reason survives, so a permanently broken setup is visible rather than
        // quietly swallowing alerts.
        assertThat(jdbc.sql("select last_error from alerts").query(String.class).single())
                .contains("connection refused");
    }

    @Test
    void aSentAlertLeavesTheQueue() {
        long groupId = seedGroup("aaa", List.of());
        long alertId =
                alertStore.raise(groupId, "spike", java.util.Optional.empty(), "boom", "pending");

        assertThat(alertStore.pending(10)).hasSize(1);

        alertStore.markSent(alertId);

        assertThat(alertStore.pending(10)).isEmpty();
        assertThat(deliveryState(alertId)).isEqualTo("sent");
    }

    private String deliveryState(long alertId) {
        return jdbc.sql("select delivery_state from alerts where id = :id")
                .param("id", alertId)
                .query(String.class)
                .single();
    }
}
