package dev.stacklight.backend.alerting;

import dev.stacklight.backend.detection.Detection;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Decides whether an alert is worth raising, and records it if so. */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final AlertStore alertStore;
    private final AlertProperties properties;
    private final JdbcClient jdbc;
    private final int cooldownMinutes;

    AlertService(
            AlertStore alertStore,
            AlertProperties properties,
            JdbcClient jdbc,
            @Value("${stacklight.detection.cooldown-minutes:60}") int cooldownMinutes) {
        this.alertStore = alertStore;
        this.properties = properties;
        this.jdbc = jdbc;
        this.cooldownMinutes = cooldownMinutes;
    }

    private String deliveryState() {
        return properties.deliveryConfigured() ? "pending" : "disabled";
    }

    private String titleOf(long groupId) {
        return jdbc.sql("select title from event_groups where id = :id")
                .param("id", groupId)
                .query(String.class)
                .single();
    }

    public Optional<Long> raiseSpike(long groupId, Detection detection) {
        return raise(groupId, "spike", Optional.of(detection));
    }

    /** Raised when a group that did not exist a few hours ago is already producing volume. */
    public Optional<Long> raiseNewGroup(long groupId) {
        return raise(groupId, "new_group", Optional.empty());
    }

    /** Raised when an event lands on a group somebody had marked resolved. */
    public Optional<Long> raiseRegression(long groupId) {
        return raise(groupId, "regression", Optional.empty());
    }

    private Optional<Long> raise(long groupId, String kind, Optional<Detection> detection) {
        if (alertStore.recentlyAlerted(groupId, cooldownMinutes)) {
            // Deliberately silent. A group in the middle of a spike produces events
            // continuously, and an alert per event is how a mailbox teaches someone to
            // filter the whole feature into a folder they never open.
            return Optional.empty();
        }

        long id = alertStore.raise(groupId, kind, detection, titleOf(groupId), deliveryState());
        log.info("alert raised id={} group={} kind={} delivery={}", id, groupId, kind, deliveryState());
        return Optional.of(id);
    }
}
