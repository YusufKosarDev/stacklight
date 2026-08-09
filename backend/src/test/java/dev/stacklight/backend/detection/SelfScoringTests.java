package dev.stacklight.backend.detection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The honesty layer: does a verdict hold up once there is hindsight to judge it by. */
@SpringBootTest
@Testcontainers
class SelfScoringTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private SelfScoringService scoring;
    @Autowired private JdbcClient jdbc;

    private long groupId;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from detector_observations").update();
        jdbc.sql("delete from event_groups").update();

        groupId =
                jdbc.sql(
                                """
                                insert into event_groups (fingerprint, fingerprint_version, service,
                                    platform, level, title, fingerprint_input, first_seen, last_seen,
                                    event_count)
                                values ('fp', 1, 'checkout-api', 'java', 'ERROR', 'boom', 'v1',
                                        now() - interval '3 days', now(), 0)
                                returning id
                                """)
                        .query(Long.class)
                        .single();
    }

    /** Puts a count into the bucket that many hours before the current one. */
    private void rollup(int hoursAgo, int count) {
        jdbc.sql(
                        """
                        insert into event_rollups (group_id, bucket_start, event_count)
                        values (:id, date_trunc('hour', now()) - make_interval(hours => :ago), :n)
                        on conflict (group_id, bucket_start) do update set event_count = excluded.event_count
                        """)
                .param("id", groupId)
                .param("ago", hoursAgo)
                .param("n", count)
                .update();
    }

    private void observation(int hoursAgo, String detector, boolean fired, int observed) {
        jdbc.sql(
                        """
                        insert into detector_observations (group_id, bucket_start, detector,
                            is_active, fired, observed, baseline, score, threshold)
                        values (:id, date_trunc('hour', now()) - make_interval(hours => :ago),
                                :detector, true, :fired, :observed, 1.0, 1.0, 3.0)
                        """)
                .param("id", groupId)
                .param("ago", hoursAgo)
                .param("detector", detector)
                .param("fired", fired)
                .param("observed", observed)
                .update();
    }

    /** Null until the bucket is old enough to judge, so the read has to tolerate null. */
    private String outcomeOf(String detector) {
        return (String)
                jdbc.sql("select outcome from detector_observations where detector = :d")
                        .param("d", detector)
                        .query()
                        .singleRow()
                        .get("outcome");
    }

    @Test
    void aBucketTooRecentToJudgeIsLeftAlone() {
        // Scoring waits for hindsight. Judging a bucket immediately would mean judging it
        // on exactly the information the detector already had, which decides nothing.
        rollup(1, 40);
        observation(1, "poisson", true, 40);

        int scored = scoring.score();

        assertThat(scored).isZero();
        assertThat(outcomeOf("poisson")).isNull();
    }

    @Test
    void firingOnAGenuineSurgeCountsAsATruePositive() {
        for (int h = 30; h >= 12; h--) {
            rollup(h, 1);
        }
        rollup(10, 40); // the surge
        rollup(9, 1);
        rollup(8, 1);

        observation(10, "poisson", true, 40);
        scoring.score();

        assertThat(outcomeOf("poisson")).isEqualTo("true_positive");
    }

    @Test
    void firingOnAnOrdinaryHourCountsAsAFalsePositive() {
        for (int h = 30; h >= 8; h--) {
            rollup(h, 20);
        }

        observation(10, "zscore", true, 22);
        scoring.score();

        assertThat(outcomeOf("zscore")).isEqualTo("false_positive");
    }

    @Test
    void stayingSilentThroughASurgeCountsAsAMiss() {
        // The number that would be missing if only firings were recorded, and the reason
        // a detector cannot improve its score by simply never firing.
        for (int h = 30; h >= 12; h--) {
            rollup(h, 1);
        }
        rollup(10, 60);
        rollup(9, 1);

        observation(10, "ewma", false, 60);
        scoring.score();

        assertThat(outcomeOf("ewma")).isEqualTo("false_negative");
    }

    @Test
    void stayingSilentThroughAnOrdinaryHourCountsAsATrueNegative() {
        for (int h = 30; h >= 8; h--) {
            rollup(h, 20);
        }

        observation(10, "poisson", false, 21);
        scoring.score();

        assertThat(outcomeOf("poisson")).isEqualTo("true_negative");
    }

    @Test
    void theOracleUsesTheHoursAfterTheBucketNotJustBefore() {
        // What separates the scorer from another detector. Before the surge the rate
        // looks like nothing; the hours after show the group settled straight back down,
        // so the spike was real rather than a shift in level.
        for (int h = 30; h >= 12; h--) {
            rollup(h, 2);
        }
        rollup(10, 50);
        for (int h = 9; h >= 5; h--) {
            rollup(h, 2);
        }

        observation(10, "poisson", true, 50);
        scoring.score();

        Double oracleBaseline =
                jdbc.sql("select oracle_baseline from detector_observations")
                        .query(Double.class)
                        .single();

        assertThat(outcomeOf("poisson")).isEqualTo("true_positive");
        // The baseline was taken from both sides, so it reflects the group's actual rate
        // rather than the run-up alone.
        assertThat(oracleBaseline).isBetween(0.5, 4.0);
    }

    @Test
    void everyDetectorOnTheSameBucketIsJudgedByTheSameRule() {
        // The comparison is only worth anything if the alternatives are scored
        // identically, at the same moment, against the same data.
        for (int h = 30; h >= 12; h--) {
            rollup(h, 1);
        }
        rollup(10, 40);
        rollup(9, 1);

        observation(10, "poisson", true, 40);
        observation(10, "zscore", true, 40);
        observation(10, "ewma", false, 40);

        scoring.score();

        assertThat(outcomeOf("poisson")).isEqualTo("true_positive");
        assertThat(outcomeOf("zscore")).isEqualTo("true_positive");
        assertThat(outcomeOf("ewma")).isEqualTo("false_negative");
    }

    @Test
    void scoringIsIdempotent() {
        for (int h = 30; h >= 12; h--) {
            rollup(h, 1);
        }
        rollup(10, 40);
        observation(10, "poisson", true, 40);

        assertThat(scoring.score()).isEqualTo(1);
        assertThat(scoring.score()).isZero();
    }

    @Test
    void aScorecardCanBeComputedFromTheOutcomes() {
        for (int h = 30; h >= 12; h--) {
            rollup(h, 1);
        }
        rollup(10, 40);
        rollup(9, 1);
        observation(10, "poisson", true, 40);
        observation(10, "ewma", false, 40);
        scoring.score();

        var row =
                jdbc.sql(
                                """
                                select detector,
                                       count(*) filter (where outcome = 'true_positive')  as tp,
                                       count(*) filter (where outcome = 'false_negative') as fn
                                  from detector_observations
                                 group by detector order by detector
                                """)
                        .query()
                        .listOfRows();

        assertThat(row).hasSize(2);
        assertThat(row.get(0)).containsEntry("detector", "ewma").containsEntry("fn", 1L);
        assertThat(row.get(1)).containsEntry("detector", "poisson").containsEntry("tp", 1L);
    }
}
