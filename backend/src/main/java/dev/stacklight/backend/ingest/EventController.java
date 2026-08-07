package dev.stacklight.backend.ingest;

import jakarta.validation.Valid;
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

    EventController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        UUID eventId = request.eventId() != null ? request.eventId() : UUID.randomUUID();
        IngestService.Result result = ingestService.ingest(eventId, request);

        log.info(
                "ingest event_id={} service={} level={} stored={} fingerprint={} group={}",
                eventId,
                request.service(),
                request.level(),
                result.stored(),
                result.fingerprint() == null ? "-" : result.fingerprint().hash(),
                result.groupId() == null ? "-" : result.groupId());

        return ResponseEntity.accepted()
                .body(
                        new IngestResponse(
                                eventId,
                                result.stored(),
                                result.fingerprint() == null ? null : result.fingerprint().hash(),
                                result.groupId()));
    }

    /**
     * @param fingerprint null when the event was a duplicate and no grouping was done
     */
    public record IngestResponse(UUID eventId, boolean stored, String fingerprint, Long groupId) {}
}
