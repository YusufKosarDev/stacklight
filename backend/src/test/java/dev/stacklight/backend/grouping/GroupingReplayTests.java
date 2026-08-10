package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stacklight.backend.ingest.IngestRequest;
import dev.stacklight.backend.ingest.IngestService;
import java.util.UUID;
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
 * The replay, against real events grouped by the real ingest path.
 *
 * <p>What is being checked is not that v2 is better — that is a judgement the report
 * exists to inform. It is that the report counts what it claims to count, because a
 * merge-and-split report that miscounts is worse than none: it would be used to justify a
 * change to history.
 */
@SpringBootTest
@Testcontainers
class GroupingReplayTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private IngestService ingestService;
    @Autowired private GroupingReplayService replayService;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
    }

    private void send(String stacktrace) {
        ingestService.ingest(
                UUID.randomUUID(),
                new IngestRequest(
                        null,
                        "checkout-api",
                        "ERROR",
                        "could not price the cart",
                        "java",
                        "java.lang.IllegalStateException",
                        stacktrace,
                        "1.0.0",
                        null));
    }

    /** Same throw site and same three frames above it, reached from two different callers. */
    private static final String VIA_CHECKOUT =
            """
            \tat com.example.CartService.total(CartService.java:42)
            \tat com.example.CartService.price(CartService.java:20)
            \tat com.example.Checkout.submit(Checkout.java:88)
            \tat com.example.CheckoutRoute.post(CheckoutRoute.java:12)
            """;

    private static final String VIA_ADMIN =
            """
            \tat com.example.CartService.total(CartService.java:42)
            \tat com.example.CartService.price(CartService.java:20)
            \tat com.example.Checkout.submit(Checkout.java:88)
            \tat com.example.AdminTools.recalculate(AdminTools.java:31)
            """;

    @Test
    void reportsTwoGroupsThatVersionTwoWouldCombine() {
        send(VIA_CHECKOUT);
        send(VIA_ADMIN);

        assertThat(countGroups()).isEqualTo(2);

        GroupingReplayService.Report report = replayService.replay(2, 1000);

        assertThat(report.merges()).hasSize(1);
        assertThat(report.merges().get(0).absorbs()).hasSize(2);
        assertThat(report.splits()).isEmpty();
    }

    @Test
    void replayingTheActiveVersionChangesNothing() {
        // The control. If v1 replayed over its own events produced merges or splits, the
        // report would be measuring the replay rather than the version.
        send(VIA_CHECKOUT);
        send(VIA_ADMIN);

        GroupingReplayService.Report report = replayService.replay(1, 1000);

        assertThat(report.merges()).isEmpty();
        assertThat(report.splits()).isEmpty();
    }

    @Test
    void reportsAGroupThatVersionTwoWouldBreakApart() {
        // Two JavaScript entry files whose only in-app frame is Object.<anonymous>.
        // V1 throws the file away, so both land in one group; v2 keeps it.
        sendJs("    at Object.<anonymous> (/app/src/index.js:1:1)\n");
        sendJs("    at Object.<anonymous> (/app/src/worker.js:1:1)\n");

        assertThat(countGroups()).isEqualTo(1);

        GroupingReplayService.Report report = replayService.replay(2, 1000);

        assertThat(report.splits()).hasSize(1);
        assertThat(report.splits().get(0).intoDistinctFingerprints()).isEqualTo(2);
    }

    @Test
    void statesHowMuchOfHistoryItCouldActuallySee() {
        // A group whose events have no stored trace cannot be replayed at all. The report
        // has to say so rather than quietly leaving it out of both lists.
        send(VIA_CHECKOUT);
        jdbc.sql("update events set stacktrace = null").update();

        GroupingReplayService.Report report = replayService.replay(2, 1000);

        assertThat(report.groupsTotal()).isEqualTo(1);
        assertThat(report.groupsCovered()).isZero();
        assertThat(report.eventsReplayed()).isZero();
    }

    private void sendJs(String stacktrace) {
        ingestService.ingest(
                UUID.randomUUID(),
                new IngestRequest(
                        null,
                        "web-ui",
                        "ERROR",
                        "x is not a function",
                        "javascript",
                        "TypeError",
                        stacktrace,
                        "1.0.0",
                        null));
    }

    private int countGroups() {
        return jdbc.sql("select count(*)::int from event_groups").query(Integer.class).single();
    }
}
