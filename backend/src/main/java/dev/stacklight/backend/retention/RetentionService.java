package dev.stacklight.backend.retention;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Deletes raw events past the retention window, and shortens that window when storage
 * gets close to the plan limit.
 *
 * <h2>Why there is no scheduler here</h2>
 *
 * The obvious design is a nightly job. It does not work on this deployment, and the
 * reason is worth stating because it generalises: the service sleeps after fifteen idle
 * minutes, and a timer that fires into a process which is not running does not fail
 * loudly, it simply does not happen. Storage then grows until the database is suspended,
 * which on this plan is the failure that takes the whole project down.
 *
 * <p>The way out is an observation rather than a workaround. <b>Storage only grows when
 * events arrive, and events can only arrive while the service is awake.</b> So retention
 * does not need a clock. It needs to run in proportion to ingest, which is exactly the
 * thing it is cleaning up after. A sweep is therefore triggered from the ingest path,
 * amortised so that most events pay nothing, plus once at startup to cover the case
 * where the service was down long enough for a backlog to build.
 *
 * <p>Every pass is bounded. A sweep deletes at most one batch, so the work it adds is
 * capped no matter how far behind it is; falling behind costs more passes, never one long
 * one.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private static final String DELETE_BATCH =
            """
            delete from events
             where id in (select id
                            from events
                           where received_at < now() - make_interval(days => :days)
                           order by id
                           limit :batch)
            """;

    private static final String EVENTS_BYTES =
            "select pg_total_relation_size('events')";

    private static final String RECORD_RUN =
            """
            insert into retention_runs (source, window_days, deleted_events, events_bytes)
            values (:source, :windowDays, :deleted, :bytes)
            """;

    private final JdbcClient jdbc;
    private final RetentionProperties properties;

    private final AtomicInteger eventsSinceSweep = new AtomicInteger();
    private final AtomicReference<Instant> lastSweep = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean sweeping = new AtomicBoolean();

    RetentionService(JdbcClient jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * Called once per stored event. Runs a sweep when enough events or enough time have
     * passed since the last one, and does nothing otherwise.
     */
    public void onEventStored() {
        int count = eventsSinceSweep.incrementAndGet();
        boolean byVolume = count >= properties.sweepEveryEvents();
        boolean byAge =
                Duration.between(lastSweep.get(), Instant.now())
                        .compareTo(Duration.ofMinutes(properties.sweepEveryMinutes()))
                        >= 0;

        if (byVolume || byAge) {
            sweep(byVolume ? "ingest-volume" : "ingest-age");
        }
    }

    /**
     * Runs one bounded sweep.
     *
     * <p>Concurrent callers are dropped rather than queued: two sweeps at once would
     * compete for the same rows, and the work is idempotent, so the second call has
     * nothing to add.
     *
     * @return number of events deleted
     */
    public long sweep(String source) {
        if (!sweeping.compareAndSet(false, true)) {
            return 0;
        }

        try {
            long bytes = jdbc.sql(EVENTS_BYTES).query(Long.class).single();
            int windowDays = windowFor(bytes);

            long deleted =
                    jdbc.sql(DELETE_BATCH)
                            .param("days", windowDays)
                            .param("batch", properties.batchSize())
                            .update();

            jdbc.sql(RECORD_RUN)
                    .param("source", source)
                    .param("windowDays", windowDays)
                    .param("deleted", deleted)
                    .param("bytes", bytes)
                    .update();

            if (deleted > 0 || windowDays != properties.windowDays()) {
                log.info(
                        "retention sweep source={} window_days={} deleted={} events_bytes={}",
                        source,
                        windowDays,
                        deleted,
                        bytes);
            }
            return deleted;
        } catch (RuntimeException e) {
            // Retention must never take ingestion down with it. A failed sweep is a
            // problem for later; a failed event is a problem now.
            log.warn("retention sweep failed source={}", source, e);
            return 0;
        } finally {
            // Both triggers are reset by an attempt rather than by a success, so a sweep
            // that keeps failing backs off exactly like one that worked: the next try
            // comes after another batch of events or another interval. Resetting them
            // only on the success path left a broken sweep running -- and logging its own
            // stack trace -- once per event for as long as it stayed broken.
            eventsSinceSweep.set(0);
            lastSweep.set(Instant.now());
            sweeping.set(false);
        }
    }

    /**
     * Picks the retention window from current storage use.
     *
     * <p>The plan suspends the project when storage runs out rather than billing for the
     * overage, so the interesting question is not "what is the nicest window" but "what
     * window keeps this alive". Under pressure the window shortens on its own; it widens
     * again as soon as the pressure is gone.
     */
    int windowFor(long eventsBytes) {
        if (eventsBytes >= properties.criticalBytes()) {
            return properties.criticalWindowDays();
        }
        if (eventsBytes >= properties.warnBytes()) {
            return properties.warnWindowDays();
        }
        return properties.windowDays();
    }
}
