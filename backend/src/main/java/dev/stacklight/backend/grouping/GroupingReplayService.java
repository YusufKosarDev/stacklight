package dev.stacklight.backend.grouping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Runs a grouping version over events that have already been grouped, and reports what
 * would change.
 *
 * <h2>Why this exists before the version is switched</h2>
 *
 * Changing the active version re-partitions history: groups can merge and split in the
 * same pass, and the cost is paid live, in public, on a dashboard someone is reading. The
 * report is the evidence for making that decision rather than discovering it.
 *
 * <h2>What it can and cannot see</h2>
 *
 * The README used to say a future version could be replayed over the stored
 * {@code fingerprint_input}. It cannot, and the difference matters. That column holds the
 * *output* of the version that wrote it — frame signatures already parsed and already
 * filtered to in-app. A version that changes parsing, or which frames count, cannot be
 * recomputed from it.
 *
 * <p>So the replay reads raw stack traces from {@code events} and runs the real
 * fingerprinter over them, which is exact. The price is coverage: retention deletes raw
 * events after fourteen days, and events over the hourly cap were stored without a trace.
 * Groups older or quieter than that have nothing to replay, so the report states how many
 * groups it could actually speak for. A merge or split it does not mention may simply be
 * one it could not see.
 */
@Service
public class GroupingReplayService {

    private static final String REPLAYABLE_EVENTS =
            """
            select e.group_id, e.service, e.level, e.platform, e.exception_type,
                   e.message, e.stacktrace
              from events e
             where e.group_id is not null
               and e.stacktrace is not null
             order by e.received_at desc
             limit :limit
            """;

    private static final String GROUP_TITLES =
            "select id, title from event_groups";

    private static final String TOTAL_GROUPS =
            "select count(*)::int from event_groups";

    private final JdbcClient jdbc;
    private final FingerprinterRegistry registry;

    GroupingReplayService(JdbcClient jdbc, FingerprinterRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    /**
     * @param groupsTotal every group in the database
     * @param groupsCovered groups with at least one event still carrying a stack trace;
     *     the rest are outside what this report can say anything about
     * @param merges one candidate fingerprint absorbing more than one existing group
     * @param splits one existing group breaking into more than one candidate fingerprint
     */
    public record Report(
            int version,
            int groupsTotal,
            int groupsCovered,
            int eventsReplayed,
            List<Merge> merges,
            List<Split> splits) {}

    public record Merge(String candidateFingerprint, List<Member> absorbs) {}

    public record Split(long groupId, String title, int intoDistinctFingerprints) {}

    public record Member(long groupId, String title) {}

    public Report replay(int version, int limit) {
        Fingerprinter fingerprinter = registry.forVersion(version);

        Map<Long, String> titles = new LinkedHashMap<>();
        jdbc.sql(GROUP_TITLES)
                .query((rs, rowNum) -> titles.put(rs.getLong("id"), rs.getString("title")))
                .list();

        int groupsTotal = jdbc.sql(TOTAL_GROUPS).query(Integer.class).single();

        // Both directions of the same mapping. One says which groups a candidate
        // fingerprint would absorb; the other says how many fingerprints a group would
        // break into. A version change can do both at once, which is exactly why there
        // is no one-to-one migration to write.
        Map<String, Set<Long>> groupsByCandidate = new LinkedHashMap<>();
        Map<Long, Set<String>> candidatesByGroup = new LinkedHashMap<>();

        List<Replayed> events =
                jdbc.sql(REPLAYABLE_EVENTS)
                        .param("limit", limit)
                        .query(
                                (rs, rowNum) ->
                                        new Replayed(
                                                rs.getLong("group_id"),
                                                rs.getString("service"),
                                                rs.getString("platform"),
                                                rs.getString("exception_type"),
                                                rs.getString("message"),
                                                rs.getString("stacktrace")))
                        .list();

        for (Replayed event : events) {
            Fingerprint candidate =
                    fingerprinter.compute(
                            new GroupingInput(
                                    event.service(),
                                    Platform.fromWireName(event.platform()),
                                    event.exceptionType(),
                                    event.message(),
                                    event.stacktrace()));

            groupsByCandidate
                    .computeIfAbsent(candidate.hash(), key -> new LinkedHashSet<>())
                    .add(event.groupId());
            candidatesByGroup
                    .computeIfAbsent(event.groupId(), key -> new LinkedHashSet<>())
                    .add(candidate.hash());
        }

        List<Merge> merges = new ArrayList<>();
        groupsByCandidate.forEach(
                (hash, groupIds) -> {
                    if (groupIds.size() > 1) {
                        merges.add(
                                new Merge(
                                        hash,
                                        groupIds.stream()
                                                .map(id -> new Member(id, titles.get(id)))
                                                .toList()));
                    }
                });

        List<Split> splits = new ArrayList<>();
        candidatesByGroup.forEach(
                (groupId, hashes) -> {
                    if (hashes.size() > 1) {
                        splits.add(new Split(groupId, titles.get(groupId), hashes.size()));
                    }
                });

        return new Report(
                version,
                groupsTotal,
                candidatesByGroup.size(),
                events.size(),
                merges,
                splits);
    }

    private record Replayed(
            long groupId,
            String service,
            String platform,
            String exceptionType,
            String message,
            String stacktrace) {}
}
