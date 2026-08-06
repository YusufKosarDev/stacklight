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

    private final EventStore store;

    EventController(EventStore store) {
        this.store = store;
    }

    @PostMapping
    public ResponseEntity<IngestResponse> ingest(@Valid @RequestBody IngestRequest request) {
        UUID eventId = request.eventId() != null ? request.eventId() : UUID.randomUUID();
        boolean stored = store.insert(eventId, request);

        log.info(
                "ingest event_id={} service={} level={} stored={}",
                eventId,
                request.service(),
                request.level(),
                stored);

        return ResponseEntity.accepted().body(new IngestResponse(eventId, stored));
    }

    public record IngestResponse(UUID eventId, boolean stored) {}
}
