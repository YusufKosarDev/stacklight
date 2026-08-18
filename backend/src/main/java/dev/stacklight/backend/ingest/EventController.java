package dev.stacklight.backend.ingest;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private static final Logger log = LoggerFactory.getLogger(EventController.class);

    private final IngestService ingestService;
    private final BatchIngestService batchIngestService;

    EventController(IngestService ingestService, BatchIngestService batchIngestService) {
        this.ingestService = ingestService;
        this.batchIngestService = batchIngestService;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        UUID eventId = request.eventId() != null ? request.eventId() : UUID.randomUUID();
        IngestService.Result result = ingestService.ingest(eventId, request);

        log.info(
                "ingest event_id={} service={} level={} stored={} fingerprint={} group={} sampled={} regressed={}",
                eventId,
                request.service(),
                request.level(),
                result.stored(),
                result.fingerprint() == null ? "-" : result.fingerprint().hash(),
                result.groupId() == null ? "-" : result.groupId(),
                result.sampled(),
                result.regressed());

        return ResponseEntity.accepted()
                .body(
                        new IngestResponse(
                                eventId,
                                result.stored(),
                                result.fingerprint() == null ? null : result.fingerprint().hash(),
                                result.groupId(),
                                result.sampled(),
                                result.regressed()));
    }

    /**
     * A queue drain in one request.
     *
     * <p>202 with a per-event result rather than 207. The request itself succeeded -- it
     * parsed, it was authorised, it was processed -- and what happened to each event is
     * data rather than transport status. 207 comes from WebDAV, is handled poorly by most
     * clients, and would carry nothing the body does not already say. The single-event
     * endpoint answers 202 as well, so a client reads the same code from both.
     *
     * <p>The whole request is rejected only when the envelope is wrong: no events, or more
     * than {@link BatchIngestService#MAX_EVENTS}. Those are client mistakes that retrying
     * cannot fix, and failing loudly is more useful than half-accepting.
     */
    @PostMapping("/batch")
    public ResponseEntity<BatchIngestService.BatchResult> ingestBatch(
            @RequestBody List<IngestRequest> events) {

        if (events == null || events.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (events.size() > BatchIngestService.MAX_EVENTS) {
            return ResponseEntity.badRequest().build();
        }

        BatchIngestService.BatchResult result = batchIngestService.ingestAll(events);

        log.info(
                "ingest batch accepted={} stored={} duplicates={} failed={}",
                result.accepted(),
                result.stored(),
                result.duplicates(),
                result.failed());

        return ResponseEntity.accepted().body(result);
    }

    /**
     * @param fingerprint null when the event was a duplicate and no grouping was done
     * @param sampled the event was counted in the trend but its detail was not kept
     * @param regressed this event brought a group back after it had been resolved
     */
    public record IngestResponse(
            UUID eventId,
            boolean stored,
            String fingerprint,
            Long groupId,
            boolean sampled,
            boolean regressed) {}
}
