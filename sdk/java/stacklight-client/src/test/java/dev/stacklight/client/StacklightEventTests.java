package dev.stacklight.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StacklightEventTests {

    private static final StacklightOptions OPTIONS =
            new StacklightOptions().service("checkout-api").release("1.4.0");

    @Test
    void theStackTraceIsWhatTheJvmPrints() {
        // The collector's parser was written against printStackTrace output. Sending
        // anything tidier would mean two formats to keep in step instead of one.
        Throwable throwable = thrownFromNestedCall();

        StacklightEvent event = StacklightEvent.from(throwable, "ERROR", OPTIONS);

        assertThat(event.stacktrace())
                .startsWith("java.lang.IllegalStateException: cart is empty")
                .containsPattern("\\n\\tat dev\\.stacklight\\.client\\.StacklightEventTests\\.[\\w$]+"
                        + "\\(StacklightEventTests\\.java:\\d+\\)");
    }

    @Test
    void aCausalChainIsIncluded() {
        Throwable cause = new IllegalArgumentException("no such cart");
        Throwable throwable = new IllegalStateException("cart is empty", cause);

        StacklightEvent event = StacklightEvent.from(throwable, "ERROR", OPTIONS);

        assertThat(event.stacktrace())
                .contains("Caused by: java.lang.IllegalArgumentException: no such cart");
    }

    @Test
    void theTypeIsFullyQualified() {
        StacklightEvent event =
                StacklightEvent.from(new IllegalStateException("boom"), "ERROR", OPTIONS);

        assertThat(event.exceptionType()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void aThrowableWithoutAMessageStillHasOne() {
        StacklightEvent event = StacklightEvent.from(new NullPointerException(), "ERROR", OPTIONS);

        assertThat(event.message()).isEqualTo("NullPointerException");
    }

    @Test
    void everyEventGetsItsOwnId() {
        StacklightEvent first = StacklightEvent.from(new IllegalStateException("a"), "ERROR", OPTIONS);
        StacklightEvent second = StacklightEvent.from(new IllegalStateException("a"), "ERROR", OPTIONS);

        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }

    @Test
    void jsonEscapesWhatWouldOtherwiseBreakIt() {
        StacklightEvent event =
                StacklightEvent.message("he said \"boom\"\n\tand \\left\\", "ERROR", OPTIONS);

        assertThat(event.toJson())
                .contains("he said \\\"boom\\\"\\n\\tand \\\\left\\\\")
                .startsWith("{\"eventId\":\"");
    }

    @Test
    void controlCharactersAreEscapedAsUnicode() {
        StringBuilder out = new StringBuilder();
        StacklightEvent.escape(out, "ab");

        assertThat(out.toString()).isEqualTo("a\\u0001b");
    }

    @Test
    void nullFieldsAreOmittedRatherThanSentAsNull() {
        StacklightEvent event =
                StacklightEvent.message("boom", "WARN", new StacklightOptions().service("svc"));

        assertThat(event.toJson()).doesNotContain("stacktrace").doesNotContain("release");
    }

    @Test
    void anOversizedMessageIsCutRatherThanRejected() {
        String huge = "x".repeat(9_000);

        StacklightEvent event = StacklightEvent.message(huge, "ERROR", OPTIONS);

        assertThat(event.message()).hasSize(4_000);
    }

    private static Throwable thrownFromNestedCall() {
        try {
            innerCall();
            throw new AssertionError("unreachable");
        } catch (IllegalStateException e) {
            return e;
        }
    }

    private static void innerCall() {
        throw new IllegalStateException("cart is empty");
    }
}
