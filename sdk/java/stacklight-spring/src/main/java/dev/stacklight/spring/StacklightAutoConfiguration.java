package dev.stacklight.spring;

import dev.stacklight.client.StacklightClient;
import dev.stacklight.client.StacklightOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Builds the client from configuration and hooks it up to the places exceptions appear. */
@AutoConfiguration
@EnableConfigurationProperties(StacklightProperties.class)
public class StacklightAutoConfiguration {

    /**
     * The client is created whether or not it is configured.
     *
     * <p>An inert client means application code can call {@code capture} unconditionally
     * and a developer running locally does not need a collector, or a second code path.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public StacklightClient stacklightClient(
            StacklightProperties properties, Environment environment) {

        String service =
                properties.getService().isBlank()
                        ? environment.getProperty("spring.application.name", "unknown")
                        : properties.getService();

        return StacklightClient.start(
                new StacklightOptions()
                        .endpoint(properties.getEndpoint())
                        .apiKey(properties.getApiKey())
                        .service(service)
                        .release(properties.getRelease())
                        .queueCapacity(properties.getQueueCapacity())
                        .batchSize(properties.getBatchSize())
                        .connectTimeout(properties.getConnectTimeout())
                        .requestTimeout(properties.getRequestTimeout())
                        .retryBaseDelay(properties.getRetryBaseDelay())
                        .retryMaxDelay(properties.getRetryMaxDelay())
                        .shutdownTimeout(properties.getShutdownTimeout())
                        .debug(properties.isDebug()));
    }

    @Bean
    @ConditionalOnClass(HandlerExceptionResolver.class)
    @ConditionalOnProperty(
            prefix = "stacklight",
            name = "capture-web-exceptions",
            havingValue = "true",
            matchIfMissing = true)
    public StacklightExceptionResolver stacklightExceptionResolver(StacklightClient client) {
        return new StacklightExceptionResolver(client);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "stacklight",
            name = "capture-uncaught-exceptions",
            havingValue = "true",
            matchIfMissing = true)
    public StacklightUncaughtExceptionInstaller stacklightUncaughtExceptionInstaller(
            ObjectProvider<StacklightClient> client) {
        return new StacklightUncaughtExceptionInstaller(client);
    }

    /**
     * Installs the uncaught handler once the context is up.
     *
     * <p>Done in a bean rather than inline so that the previous handler is captured at the
     * moment of installation and delegated to, instead of being replaced.
     */
    public static class StacklightUncaughtExceptionInstaller {

        public StacklightUncaughtExceptionInstaller(ObjectProvider<StacklightClient> client) {
            StacklightClient resolved = client.getIfAvailable();
            if (resolved == null) {
                return;
            }
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(
                    new StacklightUncaughtExceptionHandler(resolved, previous));
        }
    }
}
