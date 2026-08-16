package dev.stacklight.backend.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * The four things worth counting on the write path, in one place.
 *
 * <p>Gathered here rather than sprinkled as {@code registry.counter(...)} calls because the
 * names and the tag values are the contract: a metric renamed in one of five files is a
 * dashboard that silently goes flat. Every meter this process publishes is declared below.
 *
 * <h2>What is deliberately not measured</h2>
 *
 * <p>Nothing is tagged with a group id, a service name taken from a request, or an
 * exception type. Those come from whoever holds an ingest key, so tagging by them would let
 * a caller create series at will -- the same cardinality problem the ceiling in
 * {@link ObservabilityConfig} exists for, except arriving through the front door. Outcomes
 * are tagged; identities are not.
 */
@Component
public class PipelineMetrics {

    private final MeterRegistry registry;
    private final Timer grouping;

    PipelineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.grouping =
                Timer.builder("stacklight.grouping")
                        .description("Time to turn one event into a fingerprint")
                        .register(registry);
    }

    /**
     * @param outcome {@code stored}, {@code duplicate} or {@code sampled} -- a fixed set, so
     *     this cannot grow beyond three series
     */
    public void ingested(String outcome, long nanos) {
        Timer.builder("stacklight.ingest")
                .description("Time to accept one event, end to end inside the transaction")
                .tag("outcome", outcome)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    public void grouped(long nanos) {
        grouping.record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Counted rather than timed: a sweep's duration says less than what it found. */
    public void swept(int silenceAlerts, int deletedEvents, int scoredObservations) {
        registry.counter("stacklight.sweep.silence.alerts").increment(silenceAlerts);
        registry.counter("stacklight.sweep.events.deleted").increment(deletedEvents);
        registry.counter("stacklight.sweep.observations.scored").increment(scoredObservations);
        registry.counter("stacklight.sweep.runs").increment();
    }

    /**
     * @param outcome {@code delivered}, {@code failed} or {@code disabled} -- the three
     *     states an alert can reach, and the reason a failure is never tagged with it
     */
    public void alertDelivery(String outcome) {
        registry.counter("stacklight.alerts.delivery", "outcome", outcome).increment();
    }
}
