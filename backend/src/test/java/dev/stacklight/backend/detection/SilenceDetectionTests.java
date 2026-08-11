package dev.stacklight.backend.detection;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stacklight.backend.alerting.AlertService;
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
 * The rule for "this group was reporting and stopped".
 *
 * <p>The trap being tested against is the one that shapes every threshold in this
 * package: 97% of group-hour buckets are empty, so a rule that fired on "nothing lately"
 * would fire on nearly every group nearly always. Most of these tests are about what must
 * <i>not</i> alert.
 */
@SpringBootTest
@Testcontainers
class SilenceDetectionTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private SilenceService silenceService;
    @Autowired private AlertService alertService;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
    }

    /** A group with rollup rows at the given hour offsets into the past. */
    private long groupWithActivityAt(String status, int... hoursAgo) {
        long id =
                jdbc.sql(
                                """
                                insert into event_groups
                                    (fingerprint, fingerprint_version, service, platform, level,
                                     title, fingerprint_input, status, event_count)
                                values (md5(random()::text), 1, 'checkout-api', 'java', 'ERROR',
                                        'IllegalStateException', 'v1', :status, 10)
                                returning id
                                """)
                        .param("status", status)
                        .query(Long.class)
                        .single();

        for (int hour : hoursAgo) {
            jdbc.sql(
                            """
                            insert into event_rollups (group_id, bucket_start, event_count)
                            values (:id,
                                    date_trunc('hour', now()) - make_interval(hours => :hour),
                                    5)
                            """)
                    .param("id", id)
                    .param("hour", hour)
                    .update();
        }
        return id;
    }

    private int alertCount() {
        return jdbc.sql("select count(*)::int from alerts where kind = 'silence'")
                .query(Integer.class)
                .single();
    }

    /**
     * Moves this group's alerts back in time.
     *
     * <p>The cooldown is the one rule here that is about elapsed time rather than about
     * the shape of the data, so testing it means moving one or the other. Moving the
     * alerts is the honest direction: the rollups stay where they are, so the group keeps
     * qualifying exactly as it would during a real quiet spell, and only the age of what
     * was already said changes.
     */
    private void ageAlertsBy(long groupId, int hours) {
        jdbc.sql(
                        """
                        update alerts
                           set created_at = created_at - make_interval(hours => :hours)
                         where group_id = :groupId
                        """)
                .param("hours", hours)
                .param("groupId", groupId)
                .update();
    }

    private Detection aDetection() {
        return new Detection("poisson", true, 40, 5.0, 9.9, 3.0);
    }

    @Test
    void aGroupThatReportedEveryHourAndStoppedIsWorthSaying() {
        // Busy for eight hours, then nothing for the last three.
        groupWithActivityAt("open", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.findGoneQuiet()).hasSize(1);
        assertThat(silenceService.check()).isEqualTo(1);
        assertThat(alertCount()).isEqualTo(1);
    }

    @Test
    void aGroupThatIsMerelyQuietIsNot() {
        // Two active hours in the window. This is what almost every group looks like,
        // and alerting on it would make the feature noise.
        groupWithActivityAt("open", 9, 10);

        assertThat(silenceService.findGoneQuiet()).isEmpty();
        assertThat(alertCount()).isZero();
    }

    @Test
    void aGroupStillReportingIsNotSilent() {
        groupWithActivityAt("open", 0, 1, 2, 4, 5, 6, 7, 8);

        assertThat(silenceService.findGoneQuiet()).isEmpty();
    }

    @Test
    void aGroupWithNoHistoryAtAllIsNotSilent() {
        // No rollups whatsoever. "Never reported" is not "stopped reporting", and this
        // is the case that would sweep up the whole table if the rule were careless.
        groupWithActivityAt("open");

        assertThat(silenceService.findGoneQuiet()).isEmpty();
    }

    @Test
    void aResolvedGroupGoingQuietIsTheFixWorking() {
        // Alerting here would mean the reward for fixing something is a message saying
        // it stopped happening.
        groupWithActivityAt("resolved", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.findGoneQuiet()).isEmpty();
    }

    @Test
    void aRegressedGroupGoingQuietStillCounts() {
        groupWithActivityAt("regressed", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.findGoneQuiet()).hasSize(1);
    }

    @Test
    void anIgnoredGroupIsNotBroughtBackByGoingQuiet() {
        groupWithActivityAt("ignored", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.findGoneQuiet()).isEmpty();
    }

    @Test
    void theSecondSweepDoesNotAlertAgainWithinTheCooldown() {
        // The condition stays true for as long as the group stays quiet, so without the
        // cooldown every sweep would send the same message again.
        groupWithActivityAt("open", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.check()).isEqualTo(1);
        assertThat(silenceService.check()).isZero();
        assertThat(alertCount()).isEqualTo(1);
    }

    @Test
    void anEpisodeProducesOneAlertAcrossEverySweepThatSeesIt() {
        // The test above passes under any cooldown at all, because both sweeps happen in
        // the same instant. This is the one that fails at 60 minutes: sweeps run every
        // three hours, so the previous alert is always older than an hour by the time the
        // next one asks, and an hour-long cooldown suppresses nothing.
        //
        // Eight sweeps at three-hour spacing is a day of a group staying quiet.
        long groupId = groupWithActivityAt("open", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.check()).isEqualTo(1);

        for (int sweep = 1; sweep < 8; sweep++) {
            ageAlertsBy(groupId, 3);
            assertThat(silenceService.check())
                    .as("sweep %d, %d hours after the alert", sweep, sweep * 3)
                    .isZero();
        }

        assertThat(alertCount()).isEqualTo(1);
    }

    @Test
    void aSpikeAlertDoesNotSwallowTheSilenceThatFollowsIt() {
        // A group that errors heavily and then dies raises both, and the second is the
        // half worth waking up for. The cooldown is per kind so the spike cannot absorb
        // it -- before that it could, because a spike inside the silence cooldown looked
        // like the silence having already been reported.
        long groupId = groupWithActivityAt("open", 4, 5, 6, 7, 8, 9, 10, 11);
        alertService.raiseSpike(groupId, aDetection());

        assertThat(silenceService.check()).isEqualTo(1);
        assertThat(alertCount()).isEqualTo(1);
    }

    @Test
    void theSilenceCooldownLeavesTheEventDrivenKindsAlone() {
        // The long cooldown belongs to one kind. A spike an hour after a silence alert is
        // a new thing happening and is still reported.
        long groupId = groupWithActivityAt("open", 4, 5, 6, 7, 8, 9, 10, 11);

        assertThat(silenceService.check()).isEqualTo(1);
        ageAlertsBy(groupId, 2);

        assertThat(alertService.raiseSpike(groupId, aDetection())).isPresent();
    }

    @Test
    void activityOlderThanTheWindowDoesNotCount() {
        // Busy yesterday, nothing since. The window is what keeps this from being a
        // permanent alert about a group that was briefly interesting last week.
        groupWithActivityAt("open", 30, 31, 32, 33, 34, 35, 36, 37);

        assertThat(silenceService.findGoneQuiet()).isEmpty();
    }
}
