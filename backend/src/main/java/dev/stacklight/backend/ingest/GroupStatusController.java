package dev.stacklight.backend.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Changes a group's state.
 *
 * <p>This lives on the ingestion service rather than the dashboard, and that is a
 * consequence of a decision made in step 0 rather than an accident. The dashboard reads
 * Postgres directly using a role that holds nothing but {@code SELECT}, which is what
 * lets it keep working while this service is asleep. Letting it write would mean granting
 * that role write access to the whole schema in order to support one button. The trade is
 * accepted knowingly: marking a group resolved can wait out a cold start, while reading
 * the dashboard cannot.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupStatusController {

    private static final Logger log = LoggerFactory.getLogger(GroupStatusController.class);

    private final GroupStatusStore store;

    GroupStatusController(GroupStatusStore store) {
        this.store = store;
    }

    /**
     * @param status one of {@code open}, {@code resolved}, {@code ignored}
     * @param release build the fix went out in; recorded so a later regression can be
     *     read against it
     */
    public record StatusUpdate(
            @NotBlank @Pattern(regexp = "open|resolved|ignored") String status,
            @Size(max = 100) String release) {}

    public record StatusResponse(long groupId, String status) {}

    @PatchMapping("/{id}")
    public ResponseEntity<StatusResponse> update(
            @PathVariable long id, @Valid @RequestBody StatusUpdate update) {

        boolean changed = store.updateStatus(id, update.status(), update.release());
        if (!changed) {
            return ResponseEntity.notFound().build();
        }

        log.info("group status group={} status={} release={}", id, update.status(), update.release());
        return ResponseEntity.ok(new StatusResponse(id, update.status()));
    }
}
