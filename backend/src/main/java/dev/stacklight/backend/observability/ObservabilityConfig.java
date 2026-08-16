package dev.stacklight.backend.observability;

import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class ObservabilityConfig {

    /**
     * Ahead of the key check, so a rejected request still gets an id and still appears in
     * the log as one line rather than as an orphan. A 401 is a thing worth being able to
     * count, and it is the shape an attack would take.
     */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE - 10);
        return registration;
    }

    /**
     * Caps how many distinct series this process can hold.
     *
     * <p>A registry grows with the number of tag combinations rather than with traffic, and
     * it grows in memory a 512 MB instance shares with everything else. The HTTP meters tag
     * by URI, and an unmatched path becomes its own series -- so a scanner walking
     * {@code /wp-admin}, {@code /.env} and a thousand other guesses would otherwise be a way
     * of filling the heap from outside.
     *
     * <p>Spring already folds unmatched paths into {@code /**}, and this is the second line
     * rather than the first: past the ceiling, new series are dropped instead of accepted.
     */
    @Bean
    MeterFilter cardinalityCeiling() {
        return MeterFilter.maximumAllowableMetrics(500);
    }
}
