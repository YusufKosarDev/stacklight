package dev.stacklight.backend.ingest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lists groups for the triage console.
 *
 * <p>Sits under {@code /api/groups}, so the console key guards it rather than the ingest
 * key -- see {@link ApiKeyFilter} for why the two are not the same privilege.
 *
 * <p>{@code no-store} because the response is the only place group data appears outside
 * the database on this side, and a shared cache holding it would outlive the key that
 * fetched it.
 */
/*
 * Deliberately not @Validated. That annotation routes parameter validation through an AOP
 * proxy which raises ConstraintViolationException -- a 500 by default. Without it the
 * framework's own method validation applies and an unknown status is the 400 it should be.
 */
@RestController
@RequestMapping("/api/groups")
public class GroupListController {

    private final GroupListStore store;

    GroupListController(GroupListStore store) {
        this.store = store;
    }

    @GetMapping
    public ResponseEntity<List<GroupListStore.Row>> list(
            @RequestParam(required = false) @Pattern(regexp = "open|resolved|ignored|regressed")
                    String status,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(store.list(status, limit));
    }
}
