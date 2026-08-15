package dev.stacklight.backend.ingest;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class IngestConfig {

    private static final Logger log = LoggerFactory.getLogger(IngestConfig.class);

    /**
     * The order of these rules is the whole of the policy, so they are declared in one
     * place and read top to bottom: triage first, then everything else. Reversing them
     * would put the console behind the ingest key without any other file changing.
     */
    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(
            @Value("${stacklight.ingest.api-key:}") String ingestKey,
            @Value("${stacklight.console.api-key:}") String consoleKey) {

        if (ingestKey.isBlank()) {
            log.warn("INGEST_API_KEY is not set - /api/** will reject every request");
        }
        if (consoleKey.isBlank()) {
            log.warn("CONSOLE_API_KEY is not set - the group console cannot read or write");
        }

        List<ApiKeyFilter.Rule> rules =
                List.of(
                        ApiKeyFilter.Rule.of(
                                "/api/groups", ApiKeyFilter.CONSOLE_HEADER, consoleKey),
                        ApiKeyFilter.Rule.of("/api", ApiKeyFilter.INGEST_HEADER, ingestKey));

        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(rules));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
