package dev.stacklight.backend.detection;

import dev.stacklight.backend.alerting.AlertService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Finds groups that were reporting reliably and have stopped.
 *
 * <h2>The one thing the ingest path cannot notice</h2>
 *
 * Every other signal in this service is raised by an event arriving. This one is raised by
 * events not arriving, so nothing on the ingest path can ever see it: absence of events is
 * absence of triggers. It needs a caller from outside the process, which is what
 * {@code POST /api/sweep} is for.
 *
 * <h2>Why "no events lately" is not the rule</h2>
 *
 * The number that shapes every threshold in this package applies here too: 97% of
 * group-hour buckets are empty. A rule that fired on "this group has had nothing for a
 * while" would fire on almost every group, almost always, and mean nothing.
 *
 * <p>So the rule is about groups that had a habit. A group qualifies when it produced
 * events in at least {@code minBusyHours} separate hours of the window before the quiet
 * period, and none at all during it. "Regularly active, then stopped" is a much smaller
 * set than "quiet", and it is the one worth a message: an application that was reporting
 * and went silent is usually an application that is down, or a reporter that broke.
 *
 * <h2>Resolved groups are not news</h2>
 *
 * A group somebody marked resolved going quiet is the fix working. Only open and
 * regressed groups are considered, or the reward for fixing something would be an alert.
 */
@Service
public class SilenceService {

    private static final Logger log = LoggerFactory.getLogger(SilenceService.class);

    /**
     * Rollup rows only exist for hours that had events -- the table has a positive-count
     * constraint -- so counting rows counts active hours directly.
     */
    private static final String GONE_QUIET =
            """
            with windowed as (
                select r.group_id,
                       count(*) filter (
                           where r.bucket_start
                                 <= date_trunc('hour', now()) - make_interval(hours => :quietHours)
                       ) as busy_hours,
                       count(*) filter (
                           where r.bucket_start
                                 > date_trunc('hour', now()) - make_interval(hours => :quietHours)
                       ) as recent_hours
                  from event_rollups r
                 where r.bucket_start
                       > date_trunc('hour', now()) - make_interval(hours => :windowHours)
                 group by r.group_id
            )
            select w.group_id
              from windowed w
              join event_groups g on g.id = w.group_id
             where w.busy_hours >= :minBusyHours
               and w.recent_hours = 0
               and g.status in ('open', 'regressed')
             order by w.group_id
            """;

    private final JdbcClient jdbc;
    private final AlertService alertService;
    private final int windowHours;
    private final int quietHours;
    private final int minBusyHours;

    SilenceService(
            JdbcClient jdbc,
            AlertService alertService,
            @Value("${stacklight.detection.silence.window-hours:24}") int windowHours,
            @Value("${stacklight.detection.silence.quiet-hours:3}") int quietHours,
            @Value("${stacklight.detection.silence.min-busy-hours:6}") int minBusyHours) {
        this.jdbc = jdbc;
        this.alertService = alertService;
        this.windowHours = windowHours;
        this.quietHours = quietHours;
        this.minBusyHours = minBusyHours;
    }

    /** @return group ids that qualified, whether or not the cooldown let an alert through */
    public List<Long> findGoneQuiet() {
        return jdbc.sql(GONE_QUIET)
                .param("windowHours", windowHours)
                .param("quietHours", quietHours)
                .param("minBusyHours", minBusyHours)
                .query(Long.class)
                .list();
    }

    /** @return how many alerts were actually raised, after the cooldown had its say */
    public int check() {
        List<Long> quiet = findGoneQuiet();
        int raised = 0;

        for (long groupId : quiet) {
            if (alertService.raiseSilence(groupId).isPresent()) {
                raised++;
            }
        }

        if (!quiet.isEmpty()) {
            log.info("silence check qualified={} alerted={}", quiet.size(), raised);
        }
        return raised;
    }
}
