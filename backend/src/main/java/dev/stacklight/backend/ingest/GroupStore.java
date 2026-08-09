package dev.stacklight.backend.ingest;

import dev.stacklight.backend.grouping.Fingerprint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Creates and updates the group an event belongs to. */
@Repository
public class GroupStore {

    /**
     * Creates the group on first sight, otherwise bumps its counter and recency.
     *
     * <p>{@code first_seen} is never touched after creation, and {@code last_seen} uses
     * {@code greatest} so that an event arriving late, after a network retry, cannot pull
     * the group's recency backwards.
     *
     * <p>The status arm is regression detection. An event landing on a group somebody
     * called resolved means the fix did not hold, and that is a different situation from
     * a group nobody has looked at yet, so it gets its own state rather than being
     * quietly reopened. The release that brought it back is recorded next to it, because
     * the first question afterwards is always which build it came in on.
     */
    private static final String UPSERT =
            """
            with existing as (
                -- Read before the upsert, in the same snapshot, so the caller can tell
                -- "this event brought the group back" from "this group is already
                -- regressed". RETURNING alone cannot: it reports the row after the
                -- update, by which point both cases look identical.
                select status from event_groups
                 where fingerprint = :fingerprint
                   and fingerprint_version = :fingerprintVersion
            ), upserted as (
            insert into event_groups (fingerprint, fingerprint_version, service, platform,
                                      level, exception_type, title, culprit,
                                      fingerprint_input, frames, degraded_reason,
                                      release_first, release_last,
                                      first_seen, last_seen, event_count)
            values (:fingerprint, :fingerprintVersion, :service, :platform,
                    :level, :exceptionType, :title, :culprit,
                    :fingerprintInput, cast(:frames as jsonb), :degradedReason,
                    :release, :release,
                    now(), now(), 1)
            on conflict (fingerprint, fingerprint_version) do update
               set last_seen   = greatest(event_groups.last_seen, excluded.last_seen),
                   event_count = event_groups.event_count + 1,
                   level       = excluded.level,
                   status      = case when event_groups.status = 'resolved'
                                      then 'regressed' else event_groups.status end,
                   regressed_at = case when event_groups.status = 'resolved'
                                       then now() else event_groups.regressed_at end,
                   regressed_in_release = case when event_groups.status = 'resolved'
                                               then excluded.release_last
                                               else event_groups.regressed_in_release end,
                   release_first = coalesce(event_groups.release_first, excluded.release_first),
                   release_last  = coalesce(excluded.release_last, event_groups.release_last)
            returning id, status
            )
            select upserted.id, upserted.status, existing.status as previous_status
              from upserted left join existing on true
            """;

    private static final String MARK_SAMPLED =
            "update event_groups set sampled_count = sampled_count + 1 where id = :id";

    /**
     * Outcome of filing an event into a group.
     *
     * @param previousStatus status before this event landed; null when the group was
     *     created by it
     */
    public record Filed(long groupId, String status, String previousStatus) {

        /** True only for the event that ended a group's resolved state. */
        public boolean broughtItBack() {
            return "resolved".equals(previousStatus);
        }
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    GroupStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Files an event into its group, creating the group if this is the first sighting. */
    public Filed upsert(
            Fingerprint fingerprint,
            String service,
            String level,
            String exceptionType,
            String release) {

        return jdbc.sql(UPSERT)
                .param("fingerprint", fingerprint.hash())
                .param("fingerprintVersion", fingerprint.version())
                .param("service", service)
                .param("platform", fingerprint.platform().wireName())
                .param("level", level)
                .param("exceptionType", exceptionType)
                .param("title", fingerprint.title())
                .param("culprit", fingerprint.culprit())
                .param("fingerprintInput", fingerprint.input())
                .param("frames", writeFrames(fingerprint))
                .param("degradedReason", fingerprint.degradedReason())
                .param("release", release)
                .query(
                        (rs, rowNum) ->
                                new Filed(
                                        rs.getLong("id"),
                                        rs.getString("status"),
                                        rs.getString("previous_status")))
                .single();
    }

    /** Records that an event was counted but its detail was not kept. */
    public void markSampled(long groupId) {
        jdbc.sql(MARK_SAMPLED).param("id", groupId).update();
    }

    private String writeFrames(Fingerprint fingerprint) {
        if (fingerprint.frames() == null || fingerprint.frames().isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(fingerprint.frames());
    }
}
