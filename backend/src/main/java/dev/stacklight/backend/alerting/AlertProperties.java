package dev.stacklight.backend.alerting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Alert delivery.
 *
 * @param to where notifications go; blank turns delivery off without turning detection off
 * @param from envelope sender, which the provider usually requires to be a verified address
 * @param dashboardUrl link put in the email so it leads somewhere useful
 * @param maxAttempts deliveries to try before marking an alert failed and leaving it alone
 * @param drainBatch alerts drained per pass
 */
@ConfigurationProperties(prefix = "stacklight.alerting")
public record AlertProperties(
        @DefaultValue("") String to,
        @DefaultValue("") String from,
        @DefaultValue("") String dashboardUrl,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("20") int drainBatch) {

    /**
     * Whether an alert can actually be emailed.
     *
     * <p>When it cannot, alerts are still detected and still recorded; they are marked
     * {@code disabled} rather than queued, so a deployment without mail configured does
     * not accumulate a backlog that would all fire at once the day someone sets it up.
     */
    public boolean deliveryConfigured() {
        return !to.isBlank() && !from.isBlank();
    }
}
