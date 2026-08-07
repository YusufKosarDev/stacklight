package dev.stacklight.backend.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stacklight.backend.grouping.Fingerprint;
import dev.stacklight.backend.grouping.FingerprinterRegistry;
import dev.stacklight.backend.grouping.Platform;
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

/** Grouping behaviour against a real PostgreSQL 17, including the V2 migration. */
@SpringBootTest
@Testcontainers
class GroupingIngestionTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final String TRACE =
            """
            \tat com.example.checkout.CartService.total(CartService.java:42)
            \tat com.example.checkout.CheckoutController.submit(CheckoutController.java:88)
            \tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)
            """;

    private static final String OTHER_TRACE =
            """
            \tat com.example.billing.InvoiceService.render(InvoiceService.java:12)
            \tat com.example.billing.InvoiceController.get(InvoiceController.java:30)
            """;

    @Autowired private IngestService ingestService;
    @Autowired private GroupStore groupStore;
    @Autowired private FingerprinterRegistry fingerprinters;
    @Autowired private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("delete from events").update();
        jdbc.sql("delete from event_groups").update();
    }

    private static IngestRequest request(String message, String trace) {
        return new IngestRequest(
                null,
                "checkout-api",
                "ERROR",
                message,
                "java",
                "java.lang.NullPointerException",
                trace,
                null);
    }

    private long groupCount() {
        return jdbc.sql("select count(*) from event_groups").query(Long.class).single();
    }

    @Test
    void theSameFaultLandsInOneGroupAndBumpsTheCounter() {
        ingestService.ingest(UUID.randomUUID(), request("cart 41 is empty", TRACE));
        ingestService.ingest(UUID.randomUUID(), request("cart 77 is empty", TRACE));
        ingestService.ingest(UUID.randomUUID(), request("cart 12 is empty", TRACE));

        assertThat(groupCount()).isEqualTo(1);
        assertThat(jdbc.sql("select event_count from event_groups").query(Long.class).single())
                .isEqualTo(3);
    }

    @Test
    void differentFaultsGetTheirOwnGroups() {
        ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));
        ingestService.ingest(UUID.randomUUID(), request("boom", OTHER_TRACE));

        assertThat(groupCount()).isEqualTo(2);
    }

    @Test
    void aRepeatedEventIdDoesNotInflateTheGroupCounter() {
        // The client retried a delivery it had already made. The event is rejected by the
        // unique constraint, and the group must not count it a second time.
        UUID eventId = UUID.randomUUID();

        IngestService.Result first = ingestService.ingest(eventId, request("boom", TRACE));
        IngestService.Result repeat = ingestService.ingest(eventId, request("boom", TRACE));

        assertThat(first.stored()).isTrue();
        assertThat(repeat.stored()).isFalse();
        assertThat(repeat.groupId()).isNull();
        assertThat(groupCount()).isEqualTo(1);
        assertThat(jdbc.sql("select event_count from event_groups").query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void eventsAreLinkedToTheirGroup() {
        IngestService.Result result =
                ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));

        Long linked =
                jdbc.sql("select group_id from events where fingerprint = :fp")
                        .param("fp", result.fingerprint().hash())
                        .query(Long.class)
                        .single();

        assertThat(linked).isEqualTo(result.groupId());
    }

    @Test
    void firstSeenIsNotMovedByLaterEvents() {
        ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));
        var firstSeen =
                jdbc.sql("select first_seen from event_groups")
                        .query(java.time.OffsetDateTime.class)
                        .single();

        ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));

        assertThat(
                        jdbc.sql("select first_seen from event_groups")
                                .query(java.time.OffsetDateTime.class)
                                .single())
                .isEqualTo(firstSeen);
    }

    @Test
    void theGroupStoresTheTextThatWasHashed() {
        ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));

        String input =
                jdbc.sql("select fingerprint_input from event_groups").query(String.class).single();

        assertThat(input)
                .contains("frame=com.example.checkout.CartService#total")
                .doesNotContain("InvocableHandlerMethod");
    }

    @Test
    void aVersionBumpOpensANewGroupAndLeavesTheOldOneUntouched() {
        IngestService.Result v1 = ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));
        ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));

        // Same hash, next version: this is what a re-fingerprinted event would look like
        // after the active version moves on.
        Fingerprint asVersionTwo =
                new Fingerprint(
                        v1.fingerprint().hash(),
                        fingerprinters.activeVersion() + 1,
                        v1.fingerprint().input(),
                        v1.fingerprint().title(),
                        v1.fingerprint().culprit(),
                        null,
                        Platform.JAVA,
                        v1.fingerprint().frames());
        long newGroupId = groupStore.upsert(asVersionTwo, "checkout-api", "ERROR", "java.lang.NullPointerException");

        assertThat(newGroupId).isNotEqualTo(v1.groupId());
        assertThat(groupCount()).isEqualTo(2);

        // The frozen group keeps its own count; the new one starts over.
        assertThat(
                        jdbc.sql("select event_count from event_groups where id = :id")
                                .param("id", v1.groupId())
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(
                        jdbc.sql("select event_count from event_groups where id = :id")
                                .param("id", newGroupId)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void trigramSearchIsAvailableForSimilarGroups() {
        ingestService.ingest(UUID.randomUUID(), request("cart is empty", TRACE));
        ingestService.ingest(UUID.randomUUID(), request("cart was empty", OTHER_TRACE));

        Long similar =
                jdbc.sql(
                                """
                                select count(*) from event_groups
                                 where similarity(title, :probe) > 0.3
                                """)
                        .param("probe", "NullPointerException: cart is empty")
                        .query(Long.class)
                        .single();

        assertThat(similar).isGreaterThanOrEqualTo(2);
    }

    @Test
    void theGroupRecordsTheInAppDecisionForEveryFrame() {
        // The dashboard cannot re-parse the trace, so what it renders has to come from
        // the same parse that produced the fingerprint.
        ingestService.ingest(UUID.randomUUID(), request("boom", TRACE));

        assertThat(
                        jdbc.sql("select frames -> 0 ->> 'declaringClass' from event_groups")
                                .query(String.class)
                                .single())
                .isEqualTo("com.example.checkout.CartService");
        assertThat(
                        jdbc.sql(
                                        """
                                        select count(*) from event_groups g,
                                             jsonb_array_elements(g.frames) frame
                                         where (frame ->> 'inApp')::boolean
                                        """)
                                .query(Long.class)
                                .single())
                .isEqualTo(2);
        assertThat(
                        jdbc.sql(
                                        """
                                        select count(*) from event_groups g,
                                             jsonb_array_elements(g.frames) frame
                                         where not (frame ->> 'inApp')::boolean
                                        """)
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
    }

    @Test
    void anEventWithoutAStackTraceStillGetsAGroup() {
        ingestService.ingest(
                UUID.randomUUID(),
                new IngestRequest(
                        null, "checkout-api", "WARN", "queue lag 4200", null, null, null, null));

        assertThat(groupCount()).isEqualTo(1);
        assertThat(
                        jdbc.sql("select degraded_reason from event_groups")
                                .query(String.class)
                                .single())
                .isEqualTo(Fingerprint.NO_FRAMES);
    }
}
