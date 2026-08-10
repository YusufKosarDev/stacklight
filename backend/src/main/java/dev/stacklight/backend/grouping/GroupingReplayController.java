package dev.stacklight.backend.grouping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reports what a grouping version would do to the groups that already exist.
 *
 * <p>Read-only, and under {@code /api} so the shared-secret filter already guards it —
 * the same reasoning that put the status endpoint there. It is a decision aid rather than
 * a feature: run it, read it, and only then decide whether to move the active version.
 */
@RestController
@RequestMapping("/api/grouping")
public class GroupingReplayController {

    private static final Logger log = LoggerFactory.getLogger(GroupingReplayController.class);

    /** Bounded by default: this reads raw events, and the point is a report, not a scan. */
    private static final int DEFAULT_LIMIT = 5000;
    private static final int MAX_LIMIT = 50000;

    private final GroupingReplayService replayService;
    private final FingerprinterRegistry registry;

    GroupingReplayController(
            GroupingReplayService replayService, FingerprinterRegistry registry) {
        this.replayService = replayService;
        this.registry = registry;
    }

    @GetMapping("/replay")
    public ResponseEntity<?> replay(
            @RequestParam int version,
            @RequestParam(required = false) Integer limit) {

        if (!registry.availableVersions().contains(version)) {
            return ResponseEntity.badRequest()
                    .body(
                            new Problem(
                                    "no fingerprinter for version " + version,
                                    registry.availableVersions()));
        }

        int bounded = Math.min(limit == null ? DEFAULT_LIMIT : Math.max(1, limit), MAX_LIMIT);
        GroupingReplayService.Report report = replayService.replay(version, bounded);

        log.info(
                "grouping replay version={} groups_total={} groups_covered={} events={} merges={} splits={}",
                version,
                report.groupsTotal(),
                report.groupsCovered(),
                report.eventsReplayed(),
                report.merges().size(),
                report.splits().size());

        return ResponseEntity.ok(report);
    }

    public record Problem(String error, java.util.List<Integer> availableVersions) {}
}
