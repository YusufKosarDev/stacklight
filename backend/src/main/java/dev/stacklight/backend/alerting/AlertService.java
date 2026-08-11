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

    private static final String SILENCE = "silence";

    private final AlertStore alertStore;
    private final AlertProperties properties;
    private final JdbcClient jdbc;
    private final int cooldownMinutes;
    private final int silenceCooldownMinutes;

    AlertService(
            AlertStore alertStore,
            AlertProperties properties,
            JdbcClient jdbc,
            @Value("${stacklight.detection.cooldown-minutes:60}") int cooldownMinutes,
            @Value("${stacklight.detection.silence.cooldown-minutes:1440}")
                    int silenceCooldownMinutes) {
        this.alertStore = alertStore;
        this.properties = properties;
        this.jdbc = jdbc;
        this.cooldownMinutes = cooldownMinutes;
        this.silenceCooldownMinutes = silenceCooldownMinutes;
    }

    /**
     * How long a kind stays quiet after it has spoken.
     *
     * <h2>Why silence cannot share the others' cooldown</h2>
     *
     * Every other kind is raised by an event arriving, so its repeats arrive as fast as
     * events do — many a minute during a burst — and an hour is a long time to hold them
     * back. Silence is raised by a sweep, and a sweep runs every three hours. An hour-long
     * cooldown expires two hours before anything can ask again, so for this kind it
     * suppresses nothing at all: the group is still quiet, still qualifies, and gets a
     * fresh alert on every sweep. The cooldown was not wrong, it was measuring against a
     * cadence this kind does not have.
     *
     * <h2>Why a day, and why that means one alert per episode</h2>
     *
     * The rule that finds a silent group also stops finding it. Qualifying needs six busy
     * hours inside a 24-hour window that ends three hours ago, so as the quiet continues
     * those busy hours slide out of the window: with the sixth-newest of them at best five
     * hours before the last event, the group stops qualifying by 19 hours after it went
     * quiet, whatever its history looked like.
     *
     * <p>A day is chosen to outlast that. The first alert can be raised no earlier than
     * three hours in, so the cooldown runs to at least 27 hours — past the point where the
     * group has dropped out of the query on its own. The two mechanisms do not race:
     * <b>a silence episode produces exactly one alert</b>, and that is a property of the
     * numbers rather than a hope.
     *
     * <p>It also decides the case where a group goes quiet, comes back, and goes quiet
     * again inside the same day. That is a second episode and the cooldown deliberately
     * holds it: a reporter that flaps in and out is one story, not two, and once a day is
     * the right ceiling for telling it.
     */
    private int cooldownFor(String kind) {
        return SILENCE.equals(kind) ? silenceCooldownMinutes : cooldownMinutes;
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

    /**
     * Raised when a group that was reporting reliably has stopped.
     *
     * <p>The only kind here that nothing on the ingest path can produce, because the
     * thing being reported is that no event arrived.
     */
    public Optional<Long> raiseSilence(long groupId) {
        return raise(groupId, SILENCE, Optional.empty());
    }

    private Optional<Long> raise(long groupId, String kind, Optional<Detection> detection) {
        if (alertStore.recentlyAlerted(groupId, kind, cooldownFor(kind))) {
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
