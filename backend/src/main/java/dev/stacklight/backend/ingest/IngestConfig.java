package dev.stacklight.backend.ingest;

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

    @Bean
    FilterRegistrationBean<IngestAuthFilter> ingestAuthFilter(
            @Value("${stacklight.ingest.api-key:}") String apiKey) {

        if (apiKey.isBlank()) {
            log.warn("INGEST_API_KEY is not set - /api/** will reject every request");
        }

        FilterRegistrationBean<IngestAuthFilter> registration =
                new FilterRegistrationBean<>(new IngestAuthFilter(apiKey));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
