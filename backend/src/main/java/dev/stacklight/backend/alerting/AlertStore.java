package dev.stacklight.backend.alerting;

import dev.stacklight.backend.detection.Detection;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The alert outbox.
 *
 * <p>An alert is a row first and an email second. The row is written in the same
 * transaction as the event that caused it, so an alert cannot be lost by a delivery that
 * fails, a mail server that is down, or an instance that is put to sleep halfway through
 * sending. Delivery updates the row afterwards; nothing about it can undo the record.
 */
@Repository
public class AlertStore {

    private static final String INSERT =
            """
            insert into alerts (group_id, kind, detector, bucket_start, observed,
                                baseline, score, threshold, title, delivery_state)
            values (:groupId, :kind, :detector, date_trunc('hour', now()), :observed,
                    :baseline, :score, :threshold, :title, :deliveryState)
            returning id
            """;

    /**
     * Whether this group raised <em>this kind</em> of alert recently.
     *
     * <p>Alert fatigue is the failure mode that makes people stop reading alerts, and once
     * that happens the whole feature is decoration. One notification per group per kind per
     * cooldown; the group page carries the detail for anyone who wants it.
     *
     * <p>The kind is part of the question rather than a detail, because the kinds are
     * different stories and a cooldown suppresses a repeat of the same one. "This group is
     * spiking" and "this group has gone silent" are not repeats of each other — they are
     * the two halves of a service that errored heavily and then died, and the second is the
     * half worth waking up for. A check that ignored the kind would let the spike swallow
     * it.
     */
    private static final String RECENTLY_ALERTED =
            """
            select exists (
                select 1 from alerts
                 where group_id = :groupId
                   and kind = :kind
                   and created_at > now() - make_interval(mins => :cooldownMinutes)
            )
            """;

    private static final String PENDING =
            """
            select a.id, a.group_id, a.kind, a.detector, a.observed, a.baseline,
                   a.score, a.title, a.delivery_attempts, g.service, g.culprit,
                   g.event_count, g.status
              from alerts a join event_groups g on g.id = a.group_id
             where a.delivery_state = 'pending'
             order by a.created_at
             limit :limit
            """;

    private static final String MARK_SENT =
            """
            update alerts
               set delivery_state = 'sent', delivered_at = now(),
                   delivery_attempts = delivery_attempts + 1, last_error = null
             where id = :id
            """;

    private static final String MARK_FAILED =
            """
            update alerts
               set delivery_attempts = delivery_attempts + 1,
                   last_error = :error,
                   -- Give up after enough tries rather than retrying for ever. The row
                   -- stays, carrying the reason, so a permanently broken mail setup is
                   -- visible instead of silently swallowing alerts.
                   delivery_state = case when delivery_attempts + 1 >= :maxAttempts
                                         then 'failed' else 'pending' end
             where id = :id
            """;

    /** A pending alert, with the group context an email needs. */
    public record PendingAlert(
            long id,
            long groupId,
            String kind,
            String detector,
            Integer observed,
            Double baseline,
            Double score,
            String title,
            int attempts,
            String service,
            String culprit,
            long eventCount,
            String status) {}

    private final JdbcClient jdbc;

    AlertStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean recentlyAlerted(long groupId, String kind, int cooldownMinutes) {
        return Boolean.TRUE.equals(
                jdbc.sql(RECENTLY_ALERTED)
                        .param("groupId", groupId)
                        .param("kind", kind)
                        .param("cooldownMinutes", cooldownMinutes)
                        .query(Boolean.class)
                        .single());
    }

    /** @param deliveryState {@code pending} when mail is configured, {@code disabled} otherwise */
    public long raise(
            long groupId,
            String kind,
            Optional<Detection> detection,
            String title,
            String deliveryState) {

        return jdbc.sql(INSERT)
                .param("groupId", groupId)
                .param("kind", kind)
                .param("detector", detection.map(Detection::detector).orElse(null))
                .param("observed", detection.map(Detection::observed).orElse(null))
                .param("baseline", detection.map(Detection::baseline).orElse(null))
                .param("score", detection.map(Detection::score).orElse(null))
                .param("threshold", detection.map(Detection::threshold).orElse(null))
                .param("title", title)
                .param("deliveryState", deliveryState)
                .query(Long.class)
                .single();
    }

    public List<PendingAlert> pending(int limit) {
        return jdbc.sql(PENDING)
                .param("limit", limit)
                .query(
                        (rs, rowNum) ->
                                new PendingAlert(
                                        rs.getLong("id"),
                                        rs.getLong("group_id"),
                                        rs.getString("kind"),
                                        rs.getString("detector"),
                                        (Integer) rs.getObject("observed"),
                                        (Double) rs.getObject("baseline"),
                                        (Double) rs.getObject("score"),
                                        rs.getString("title"),
                                        rs.getInt("delivery_attempts"),
                                        rs.getString("service"),
                                        rs.getString("culprit"),
                                        rs.getLong("event_count"),
                                        rs.getString("status")))
                .list();
    }

    public void markSent(long alertId) {
        jdbc.sql(MARK_SENT).param("id", alertId).update();
    }

    public void markFailed(long alertId, String error, int maxAttempts) {
        jdbc.sql(MARK_FAILED)
                .param("id", alertId)
                .param("error", error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500)))
                .param("maxAttempts", maxAttempts)
                .update();
    }
}
