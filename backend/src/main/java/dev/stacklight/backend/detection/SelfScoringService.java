package dev.stacklight.backend.detection;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Goes back over old verdicts and marks each one right or wrong.
 *
 * <h2>What the score actually measures, and what it does not</h2>
 *
 * A detector judging an hour has only the hours before it. Some time later there is more
 * to go on: the hours after. This scorer uses that, which is the only reason it can
 * disagree with a detector at all — it is not a better detector, it is the same problem
 * with information the detector was not allowed to have.
 *
 * <p>An hour counts as a genuine surge when its count stands well clear of the rate around
 * it, taken from <i>both</i> sides. From that, each detector's verdict on that hour falls
 * into one of four boxes, and precision and recall follow.
 *
 * <p><b>This is not accuracy, and the dashboard says so.</b> Nobody is labelling these
 * hours by hand; the reference is a rule, so the numbers measure agreement with a rule
 * applied in hindsight. A detector could score well by matching a bad rule. What the
 * numbers are good for is comparing detectors against each other on identical data, which
 * is the question actually being asked. They are shown unfiltered, including the runs that
 * make the active detector look worse than a shadow.
 */
@Service
public class SelfScoringService {

    private static final Logger log = LoggerFactory.getLogger(SelfScoringService.class);

    /**
     * Scores every verdict old enough to judge, in one pass.
     *
     * <p>Written as a single statement so that the reference rule cannot drift between the
     * detectors being compared: they are all scored by the same expression, at the same
     * moment, against the same rollups.
     */
    private static final String SCORE =
            """
            with scorable as (
                select o.id, o.group_id, o.bucket_start, o.observed, o.fired, g.first_seen
                  from detector_observations o
                  join event_groups g on g.id = o.group_id
                 where o.outcome is null
                   and o.bucket_start <= date_trunc('hour', now())
                                         - make_interval(hours => :scoreAfterHours)
                 order by o.bucket_start
                 limit :batch
            ),
            judged as (
                select s.id,
                       ctx.baseline,
                       (s.observed >= :minObserved
                        and s.observed >= greatest(:minObserved,
                                                   ctx.baseline * :oracleMultiplier)) as oracle,
                       s.fired
                  from scorable s
                  cross join lateral (
                      -- Mean over the hours either side, the bucket itself excluded, and
                      -- never reaching back past the group's first sighting. Empty hours
                      -- are counted as zero rather than skipped: a quiet hour is evidence
                      -- about the rate, and dropping it would flatter every spike.
                      select coalesce(sum(r.event_count), 0)::float8
                             / greatest(1, count(*))::float8 as baseline
                        from generate_series(
                                 greatest(date_trunc('hour', s.first_seen),
                                          s.bucket_start - interval '24 hours'),
                                 s.bucket_start + make_interval(hours => :scoreAfterHours),
                                 interval '1 hour') as b
                        left join event_rollups r
                               on r.group_id = s.group_id and r.bucket_start = b
                       where b <> s.bucket_start
                  ) ctx
            )
            update detector_observations o
               set outcome = case
                                when j.fired and j.oracle       then 'true_positive'
                                when j.fired and not j.oracle   then 'false_positive'
                                when not j.fired and j.oracle   then 'false_negative'
                                else 'true_negative'
                             end,
                   oracle_baseline = j.baseline,
                   scored_at = now()
              from judged j
             where o.id = j.id
            """;

    private static final int BATCH = 500;

    private final JdbcClient jdbc;
    private final DetectorProperties properties;

    private final AtomicReference<Instant> lastRun = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean running = new AtomicBoolean();

    SelfScoringService(JdbcClient jdbc, DetectorProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Scores whatever has aged into range, at most once every few minutes.
     *
     * <p>Triggered from the ingest path and at startup rather than on a timer, for the
     * reason retention is: on an instance that sleeps, a scheduled job is a job that
     * quietly does not run.
     */
    public int scoreIfDue() {
        if (Duration.between(lastRun.get(), Instant.now()).compareTo(Duration.ofMinutes(5)) < 0) {
            return 0;
        }
        return score();
    }

    public int score() {
        if (!running.compareAndSet(false, true)) {
            return 0;
        }
        try {
            int scored =
                    jdbc.sql(SCORE)
                            .param("scoreAfterHours", properties.scoreAfterHours())
                            .param("batch", BATCH)
                            .param("minObserved", properties.minObserved())
                            .param("oracleMultiplier", properties.oracleMultiplier())
                            .update();

            lastRun.set(Instant.now());
            if (scored > 0) {
                log.info("scored {} detector observations", scored);
            }
            return scored;
        } catch (RuntimeException e) {
            log.warn("self-scoring pass failed", e);
            return 0;
        } finally {
            running.set(false);
        }
    }
}
