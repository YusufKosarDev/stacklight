package dev.stacklight.spring;

import static org.assertj.core.api.Assertions.assertThat;

import dev.stacklight.client.StacklightClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.ModelAndView;

class StacklightAutoConfigurationTests {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StacklightAutoConfiguration.class));

    @Test
    void aClientExistsEvenWithNothingConfigured() {
        // Application code should be able to call capture unconditionally, and a
        // developer running locally should not need a collector or a second code path.
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(StacklightClient.class);
                    StacklightClient client = context.getBean(StacklightClient.class);
                    client.capture(new IllegalStateException("boom"));
                    assertThat(client.stats().accepted()).isZero();
                });
    }

    @Test
    void configurationIsReadFromTheStacklightPrefix() {
        runner.withPropertyValues(
                        "stacklight.endpoint=https://collector.invalid/api/events",
                        "stacklight.api-key=secret",
                        "stacklight.service=checkout-api",
                        "stacklight.release=1.4.0",
                        "stacklight.queue-capacity=64")
                .run(
                        context -> {
                            StacklightProperties properties = context.getBean(StacklightProperties.class);
                            assertThat(properties.getService()).isEqualTo("checkout-api");
                            assertThat(properties.getQueueCapacity()).isEqualTo(64);
                        });
    }

    @Test
    void theServiceNameFallsBackToTheApplicationName() {
        runner.withPropertyValues("spring.application.name=billing-worker")
                .run(context -> assertThat(context).hasSingleBean(StacklightClient.class));
    }

    @Test
    void theResolverIsRegisteredAndObservesBeforeAnyoneElse() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(StacklightExceptionResolver.class);
                    assertThat(context.getBean(StacklightExceptionResolver.class).getOrder())
                            .isEqualTo(Ordered.HIGHEST_PRECEDENCE);
                });
    }

    @Test
    void theResolverReportsWithoutHandling() {
        // Returning null is what keeps this out of the way: the application's own error
        // handling proceeds exactly as it would have, and the response is unchanged.
        runner.withPropertyValues(
                        "stacklight.endpoint=https://collector.invalid/api/events",
                        "stacklight.api-key=secret")
                .run(
                        context -> {
                            StacklightExceptionResolver resolver =
                                    context.getBean(StacklightExceptionResolver.class);
                            StacklightClient client = context.getBean(StacklightClient.class);

                            ModelAndView handled =
                                    resolver.resolveException(
                                            null, null, null, new IllegalStateException("boom"));

                            assertThat(handled).isNull();
                            assertThat(client.stats().accepted()).isEqualTo(1);
                        });
    }

    @Test
    void webCaptureCanBeTurnedOff() {
        runner.withPropertyValues("stacklight.capture-web-exceptions=false")
                .run(context -> assertThat(context).doesNotHaveBean(StacklightExceptionResolver.class));
    }

    @Test
    void anApplicationThatDefinesItsOwnClientKeepsIt() {
        runner.withBean(
                        StacklightClient.class,
                        () -> StacklightClient.start(new dev.stacklight.client.StacklightOptions()))
                .run(context -> assertThat(context).hasSingleBean(StacklightClient.class));
    }

    @Test
    void theUncaughtHandlerDelegatesRatherThanReplacing() {
        // Installing a default handler takes something that belonged to the application.
        // Delegating is what keeps that from being a change in behaviour.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        boolean[] delegateRan = {false};
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> delegateRan[0] = true);

        try {
            StacklightClient client = StacklightClient.start(new dev.stacklight.client.StacklightOptions());
            new StacklightUncaughtExceptionHandler(client, Thread.getDefaultUncaughtExceptionHandler())
                    .uncaughtException(Thread.currentThread(), new IllegalStateException("boom"));

            assertThat(delegateRan[0]).isTrue();
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }
}
