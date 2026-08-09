package dev.stacklight.backend.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Sweeps once when the service comes up.
 *
 * <p>This is the arm of retention that covers a long absence. The ingest-path sweep keeps
 * up with events as they arrive, but says nothing about a service that was asleep for a
 * week while its last events aged out of the window. Waking is the first moment anything
 * can be done about that, so it is done then.
 *
 * <p>Run off the startup thread: this instance takes over a minute to come up cold, and
 * nothing about deleting old rows should be added to that.
 */
@Component
public class RetentionStartupSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionStartupSweep.class);

    private final RetentionService retentionService;
    private final TaskExecutor taskExecutor;

    RetentionStartupSweep(RetentionService retentionService, TaskExecutor taskExecutor) {
        this.retentionService = retentionService;
        this.taskExecutor = taskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweepOnStartup() {
        taskExecutor.execute(
                () -> {
                    long deleted = retentionService.sweep("startup");
                    log.info("startup retention sweep deleted={}", deleted);
                });
    }
}
