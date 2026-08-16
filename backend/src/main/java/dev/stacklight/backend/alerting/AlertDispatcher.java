package dev.stacklight.backend.alerting;

import dev.stacklight.backend.observability.PipelineMetrics;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Drains the alert outbox.
 *
 * <p>Delivery is deliberately separated from detection. Detection happens inside the
 * transaction that stored the event, so an alert is durable the moment it is decided;
 * sending is a best-effort pass afterwards that can fail, be interrupted by the instance
 * going to sleep, or not be configured at all, without any of that being able to lose the
 * alert.
 *
 * <p>Drained on the same two triggers retention uses, and for the same reason: a queue
 * that is only worked by a timer is a queue that stops being worked the moment the process
 * is not running.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final AlertStore alertStore;
    private final AlertProperties properties;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final TaskExecutor taskExecutor;
    private final PipelineMetrics metrics;
    private final AtomicBoolean draining = new AtomicBoolean();

    AlertDispatcher(
            AlertStore alertStore,
            AlertProperties properties,
            ObjectProvider<JavaMailSender> mailSender,
            TaskExecutor taskExecutor,
            PipelineMetrics metrics) {
        this.alertStore = alertStore;
        this.properties = properties;
        this.mailSender = mailSender;
        this.taskExecutor = taskExecutor;
        this.metrics = metrics;
    }

    /** Kicks a drain onto a background thread. Never blocks the caller. */
    public void requestDrain() {
        if (!properties.deliveryConfigured()) {
            return;
        }
        taskExecutor.execute(this::drain);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void drainOnStartup() {
        requestDrain();
    }

    /**
     * Sends what is waiting.
     *
     * @return number of alerts delivered
     */
    public int drain() {
        if (!properties.deliveryConfigured() || !draining.compareAndSet(false, true)) {
            return 0;
        }

        try {
            JavaMailSender sender = mailSender.getIfAvailable();
            if (sender == null) {
                // Address configured but no mail host: nothing to do, and saying so once
                // per drain is more useful than failing every alert individually.
                log.warn("alert delivery configured but no mail sender is available");
                return 0;
            }

            List<AlertStore.PendingAlert> pending = alertStore.pending(properties.drainBatch());
            int sent = 0;

            for (AlertStore.PendingAlert alert : pending) {
                try {
                    sender.send(compose(alert));
                    alertStore.markSent(alert.id());
                    metrics.alertDelivery("delivered");
                    sent++;
                } catch (RuntimeException e) {
                    alertStore.markFailed(alert.id(), e.getMessage(), properties.maxAttempts());
                    metrics.alertDelivery("failed");
                    log.warn("alert delivery failed id={} attempt={}", alert.id(), alert.attempts() + 1, e);
                }
            }

            if (sent > 0) {
                log.info("delivered {} alerts", sent);
            }
            return sent;
        } finally {
            draining.set(false);
        }
    }

    private SimpleMailMessage compose(AlertStore.PendingAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(properties.to());
        message.setSubject("[" + alert.service() + "] " + subjectFor(alert) + ": " + alert.title());

        StringBuilder body = new StringBuilder();
        body.append(bodyIntro(alert)).append("\n\n");
        body.append("Group     ").append(alert.title()).append('\n');
        body.append("Service   ").append(alert.service()).append('\n');
        body.append("Culprit   ").append(alert.culprit() == null ? "-" : alert.culprit()).append('\n');
        body.append("Events    ").append(alert.eventCount()).append(" total\n");
        body.append("Status    ").append(alert.status()).append('\n');

        if (alert.detector() != null) {
            body.append('\n');
            body.append("Detector  ").append(alert.detector()).append('\n');
            body.append("Observed  ").append(alert.observed()).append(" this hour\n");
            body.append("Baseline  ").append(String.format("%.2f", alert.baseline())).append('\n');
            body.append("Score     ").append(String.format("%.2f", alert.score())).append('\n');
        }

        if (!properties.dashboardUrl().isBlank()) {
            body.append('\n')
                    .append(properties.dashboardUrl())
                    .append("/groups/")
                    .append(alert.groupId())
                    .append('\n');
        }

        message.setText(body.toString());
        return message;
    }

    private static String subjectFor(AlertStore.PendingAlert alert) {
        return switch (alert.kind()) {
            case "spike" -> "spike";
            case "new_group" -> "new error";
            case "regression" -> "regression";
            default -> alert.kind();
        };
    }

    private static String bodyIntro(AlertStore.PendingAlert alert) {
        return switch (alert.kind()) {
            case "spike" -> "This group is producing errors faster than its recent rate.";
            case "new_group" -> "A group that did not exist a few hours ago is producing volume.";
            case "regression" ->
                    "This group had been marked resolved and has started happening again.";
            default -> "Something changed on this group.";
        };
    }
}
