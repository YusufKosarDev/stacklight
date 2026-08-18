package dev.stacklight.backend.scale;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the data layer does when there is data in it.
 *
 * <p>This project measures nearly everything about itself and had measured nothing about
 * this. Production holds fifteen groups and a couple of thousand events, at which size
 * PostgreSQL is right to read every table end to end -- so the eight thousand sequential
 * scans on {@code event_groups} say nothing at all about whether the indexes are the right
 * ones. This finds out, by putting enough rows in a real PostgreSQL to make the planner
 * change its mind and recording where it does.
 *
 * <h2>Not against production, and not for want of nerve</h2>
 *
 * <p>The live database is a Neon free plan with a 512 MB ceiling that suspends the project
 * rather than billing for an overage, and deleting rows does not hand the space back
 * promptly. Loading two hundred thousand events into it and hoping the cleanup works is
 * betting a live deployment on a tidy-up. A container costs nothing and is thrown away.
 *
 * <p><b>What transfers and what does not.</b> The planner and the statistics are the same
 * here as on Neon, so the plan shapes and index choices below are the ones production would
 * make at these sizes. Neon's storage is disaggregated and its timings are its own, so the
 * milliseconds are container milliseconds and are reported as such. Nothing here is a claim
 * about how fast the live deployment is.
 *
 * <p>Excluded from {@code mvn verify} by its tag: it takes minutes and CI takes seconds.
 * Run it with {@code -Dgroups=scale} or through the workflow that exists for it.
 */
@Tag("scale")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "stacklight.ingest.api-key=" + ScaleExperimentTests.API_KEY,
            // Off: the point is to measure the data layer, not to have a scoring pass
            // wander through a million rows in the middle of a timing.
            "stacklight.retention.sweep-every-events=100000000"
        })
@Testcontainers
class ScaleExperimentTests {

    static final String API_KEY = "scale-key";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort private int port;

    @Autowired private JdbcClient jdbc;

    /** groups, rollup hours per group, events, observations, alerts */
    private record Stage(String label, int groups, int hours, int events, int observations, int alerts) {}

    private static final List<Stage> STAGES =
            List.of(
                    new Stage("1k", 50, 20, 2_000, 1_000, 50),
                    new Stage("10k", 250, 40, 10_000, 5_000, 250),
                    new Stage("100k", 1_000, 100, 50_000, 25_000, 1_000),
                    new Stage("1M", 5_000, 200, 200_000, 200_000, 5_000));

    @Test
    void measureTheDataLayerAsItGrows() throws Exception {
        ScaleSeeder seeder = new ScaleSeeder(jdbc);
        List<String> report = new ArrayList<>();
        // Printed as it is produced, not collected and printed at the end: a failure in
        // a later stage would otherwise take the earlier ones down with it, and the
        // stages that did complete are the measurement.
        java.util.function.Consumer<String> say =
                line -> {
                    report.add(line);
                    System.out.println(line);
                };

        say.accept("");
        say.accept("=".repeat(96));
        say.accept("SCALE EXPERIMENT -- real PostgreSQL 17 in a container, container timings");
        say.accept("=".repeat(96));

        int groupsSoFar = 0;

        for (Stage stage : STAGES) {
            seeder.groups(groupsSoFar + 1, stage.groups());
            groupsSoFar = stage.groups();
            seeder.rollups(stage.hours());
            seeder.events(stage.events() - (int) seeder.count("events"));
            seeder.observations(stage.observations());
            seeder.alerts(stage.alerts() - (int) seeder.count("alerts"));
            seeder.analyze();

            say.accept("");
            say.accept("-".repeat(96));
            say.accept(
                    "STAGE %s   groups=%,d  events=%,d  rollups=%,d  observations=%,d  alerts=%,d  size=%s"
                            .formatted(
                                    stage.label(),
                                    seeder.count("event_groups"),
                                    seeder.count("events"),
                                    seeder.count("event_rollups"),
                                    seeder.count("detector_observations"),
                                    seeder.count("alerts"),
                                    seeder.totalSize()));
            say.accept("-".repeat(96));
            say.accept("  %-34s %-46s %8s %9s".formatted("QUERY", "PLAN", "ms", "buffers"));

            for (Map.Entry<String, String> query : queries().entrySet()) {
                say.accept(explain(query.getKey(), query.getValue()));
            }

            say.accept("  %-34s %-46s %8s %9s".formatted("-- write path --", "", "", ""));
            say.accept(timedBatch());
            say.accept(timedSweep());
        }

        say.accept("");
        say.accept("=".repeat(96));

        // The experiment is a measurement, not a threshold: the numbers go in the README
        // and a regression is judged by reading them. The one thing asserted is that it
        // actually built the shape it claims to have measured.
        assertThat(seeder.count("event_rollups")).isGreaterThanOrEqualTo(1_000_000);
        assertThat(seeder.count("event_groups")).isEqualTo(5_000);
    }

    // ---- the statements under test -----------------------------------------------------

    /**
     * The dashboard's read path, transcribed.
     *
     * <p>Those queries live in {@code web/lib/queries.ts} and run through a driver that
     * speaks Neon's HTTP protocol, which a container cannot answer -- so they are copied
     * here. A copy can drift from its original and then this measures a fiction, which is
     * why {@link TranscribedQueryTests} exists, and why it runs in CI while this does not.
     */
    private Map<String, String> queries() {
        Map<String, String> queries = new LinkedHashMap<>();

        queries.put(
                "listGroups first page",
                """
                select g.id, g.title, g.service, g.status, g.event_count
                  from event_groups g
                 where (null::text is null or g.service = null)
                 order by g.last_seen desc, g.id desc
                 limit 26
                """);

        queries.put(
                "listGroups deep page (keyset)",
                """
                select g.id, g.title, g.service, g.status, g.event_count
                  from event_groups g
                 where (g.last_seen, g.id)
                       < ((select last_seen from event_groups order by last_seen desc, id desc
                            offset 2000 limit 1),
                          (select id from event_groups order by last_seen desc, id desc
                            offset 2000 limit 1))
                 order by g.last_seen desc, g.id desc
                 limit 26
                """);

        queries.put(
                "countsByStatus",
                """
                select g.status, count(*)::int as n from event_groups g group by g.status
                """);

        queries.put(
                "getOverviewTrend (7d)",
                """
                select coalesce(sum(r.event_count), 0)::int as count
                  from generate_series(date_trunc('day', now()) - make_interval(days => 6),
                                       date_trunc('day', now()), interval '1 day') as bucket
                  left join event_rollups r
                         on date_trunc('day', r.bucket_start) = bucket
                        and r.group_id in (select g.id from event_groups g)
                 group by bucket order by bucket
                """);

        queries.put(
                "listSparklines (25 ids)",
                """
                select r.group_id,
                       extract(day from (date_trunc('day', now()) - date_trunc('day', r.bucket_start))) as days_ago,
                       sum(r.event_count)::int as event_count
                  from event_rollups r
                 where r.group_id = any(
                         (select array_agg(id) from (select id from event_groups
                           order by last_seen desc limit 25) x)::bigint[])
                   and r.bucket_start >= date_trunc('day', now()) - make_interval(days => 6)
                 group by r.group_id, days_ago
                """);

        queries.put(
                "getGroupSeries (30d)",
                """
                select coalesce(sum(r.event_count), 0)::int as count
                  from generate_series(date_trunc('day', now()) - make_interval(days => 29),
                                       date_trunc('day', now()), interval '1 day') as bucket
                  left join event_rollups r
                         on r.group_id = 1 and date_trunc('day', r.bucket_start) = bucket
                 group by bucket order by bucket
                """);

        queries.put(
                "findSimilarGroups (pg_trgm)",
                """
                select g.id, g.title, similarity(g.title, 'could not reserve stock for cart') as score
                  from event_groups g
                 where g.id <> 1 and g.title % 'could not reserve stock for cart'
                 order by score desc limit 5
                """);

        queries.put(
                "getNavCounts",
                """
                select (select count(*)::int from event_groups where status in ('open','regressed')),
                       (select count(*)::int from alerts)
                """);

        queries.put(
                "listAlerts (50)",
                """
                select a.id, a.kind, a.title, g.service
                  from alerts a join event_groups g on g.id = a.group_id
                 order by a.created_at desc limit 50
                """);

        queries.put(
                "getDetectorScorecard",
                """
                select detector, outcome, count(*)::int as n
                  from detector_observations
                 where outcome is not null
                 group by detector, outcome
                """);

        return queries;
    }

    // ---- measurement -------------------------------------------------------------------

    private String explain(String label, String sql) {
        List<String> lines =
                jdbc.sql("explain (analyze, buffers, format text) " + sql)
                        .query(String.class)
                        .list();

        String plan =
                lines.stream()
                        .findFirst()
                        .map(line -> line.trim().split("\\(cost")[0].trim())
                        .orElse("?");

        String time =
                lines.stream()
                        .filter(line -> line.contains("Execution Time:"))
                        .findFirst()
                        .map(line -> line.replaceAll(".*Execution Time: ([0-9.]+) ms.*", "$1"))
                        .orElse("?");

        long buffers =
                lines.stream()
                        .filter(line -> line.contains("Buffers:"))
                        .mapToLong(ScaleExperimentTests::readShared)
                        .sum();

        return "  %-34s %-46s %8s %9s"
                .formatted(label, truncate(plan, 46), time, String.format("%,d", buffers));
    }

    private static long readShared(String line) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("shared (?:hit|read)=(\\d+)").matcher(line);
        long total = 0;
        while (matcher.find()) {
            total += Long.parseLong(matcher.group(1));
        }
        return total;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    /** A real batch through the real endpoint: grouping, upsert, rollup, detection, all of it. */
    private String timedBatch() throws Exception {
        String body =
                IntStream.range(0, 20)
                        .mapToObj(
                                i ->
                                        """
                                        {"eventId":"%s","service":"scale-probe","level":"ERROR",
                                         "message":"probe %d","platform":"java",
                                         "exceptionType":"java.lang.IllegalStateException",
                                         "stacktrace":"java.lang.IllegalStateException: probe\\n\\tat com.example.Probe.run(Probe.java:%d)"}
                                        """
                                                .formatted(UUID.randomUUID(), i, i))
                        .collect(Collectors.joining(",", "[", "]"));

        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events/batch"))
                        .header("Content-Type", "application/json")
                        .header("X-Stacklight-Key", API_KEY)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

        long started = System.nanoTime();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        double ms = (System.nanoTime() - started) / 1_000_000.0;

        return "  %-34s %-46s %8.1f %9s"
                .formatted(
                        "POST /api/events/batch (20)",
                        "status " + response.statusCode() + ", " + String.format("%.1f", ms / 20) + " ms/event",
                        ms,
                        "");
    }

    /** One bounded retention pass over whatever is in the events table by now. */
    private String timedSweep() {
        long started = System.nanoTime();
        long deleted =
                jdbc.sql(
                                """
                                with doomed as (
                                    select id from events
                                     where received_at < now() - interval '14 days'
                                     limit 5000
                                )
                                delete from events where id in (select id from doomed)
                                """)
                        .update();
        double ms = (System.nanoTime() - started) / 1_000_000.0;

        return "  %-34s %-46s %8.1f %9s"
                .formatted("retention sweep (one batch)", "deleted " + deleted, ms, "");
    }

}
