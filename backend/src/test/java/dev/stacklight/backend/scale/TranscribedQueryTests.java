package dev.stacklight.backend.scale;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Keeps the scale experiment honest about what it is measuring.
 *
 * <p>The dashboard's read queries live in {@code web/lib/queries.ts} and reach Postgres
 * through a driver that speaks Neon's HTTP protocol, which a container cannot answer. So
 * {@link ScaleExperimentTests} works from copies. A copy can drift from its original, and
 * then the experiment measures a query nobody runs while still printing a convincing table.
 *
 * <p>Each copy therefore names a fragment distinctive enough to pin, and this checks the
 * fragment is still in the file it came from. It is deliberately not part of the experiment:
 * that one is tagged out of CI because it takes minutes, and a guard that only runs when
 * somebody remembers to run it is not a guard. This needs no database and no container, so
 * it runs on every push with everything else.
 *
 * <p>It is a spelling check rather than a proof. A rewritten query that happened to keep the
 * fragment would pass. What it catches is the drift that actually happens: a WHERE clause or
 * an ORDER BY changing on one side and not the other.
 */
class TranscribedQueryTests {

    /**
     * Fragments the experiment transcribes, each distinctive enough that its presence means
     * the shape being measured is still the shape being served.
     */
    private static final List<String> FRAGMENTS =
            List.of(
                    "order by g.last_seen desc, g.id desc",
                    "(g.last_seen, g.id) < (",
                    "group by g.status",
                    "date_trunc('day', now()) - make_interval(days => 6)",
                    "date_trunc('day', r.bucket_start) = bucket",
                    "where status in ('open', 'regressed')",
                    "order by a.created_at desc",
                    "where outcome is not null");

    @Test
    void everyTranscribedFragmentIsStillInTheDashboardsQueries() throws Exception {
        Path source = Path.of("..", "web", "lib", "queries.ts");

        assertThat(source)
                .withFailMessage(
                        "web/lib/queries.ts is where the read path lives; the scale"
                                + " experiment copies from it and cannot check itself without it")
                .exists();

        String queries = normalise(Files.readString(source));

        for (String fragment : FRAGMENTS) {
            assertThat(queries)
                    .withFailMessage(
                            "the scale experiment transcribes \"%s\" but web/lib/queries.ts no"
                                    + " longer contains it -- one of the two has moved, and the"
                                    + " experiment is measuring a query that is not served",
                            fragment)
                    .contains(normalise(fragment));
        }
    }

    /** Whitespace is not the contract; the statement is. */
    private static String normalise(String sql) {
        return sql.replaceAll("\\s+", " ");
    }
}
