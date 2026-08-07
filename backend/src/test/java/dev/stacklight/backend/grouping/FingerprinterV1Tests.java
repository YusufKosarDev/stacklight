package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FingerprinterV1Tests {

    private final FingerprinterV1 fingerprinter =
            new FingerprinterV1(
                    List.of(new JavaStackTraceParser(), new JavaScriptStackTraceParser()),
                    new MessageNormalizer());

    private static final String TRACE_BEFORE_EDIT =
            """
            \tat com.example.checkout.CartService.total(CartService.java:42)
            \tat com.example.checkout.CheckoutController.submit(CheckoutController.java:88)
            \tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)
            """;

    /** Same code path, but unrelated lines were added above the throw site. */
    private static final String TRACE_AFTER_EDIT =
            """
            \tat com.example.checkout.CartService.total(CartService.java:57)
            \tat com.example.checkout.CheckoutController.submit(CheckoutController.java:91)
            \tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:262)
            """;

    private GroupingInput java(String message, String trace) {
        return new GroupingInput(
                "checkout-api", Platform.JAVA, "java.lang.NullPointerException", message, trace);
    }

    @Test
    void sameFaultProducesSameFingerprintAcrossLineNumberShifts() {
        Fingerprint before = fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT));
        Fingerprint after = fingerprinter.compute(java("boom", TRACE_AFTER_EDIT));

        assertThat(before.hash()).isEqualTo(after.hash());
        assertThat(before.degradedReason()).isNull();
    }

    @Test
    void messageDifferencesDoNotSplitAGroupWhenFramesArePresent() {
        // Runtimes reword messages between releases and messages carry values; grouping
        // on them would scatter one fault across many groups.
        Fingerprint first = fingerprinter.compute(java("cart 41 is empty", TRACE_BEFORE_EDIT));
        Fingerprint second = fingerprinter.compute(java("cart 77 is empty", TRACE_BEFORE_EDIT));

        assertThat(first.hash()).isEqualTo(second.hash());
    }

    @Test
    void vendorFramesAloneDoNotDecideTheGroup() {
        String sameAppPathDifferentFramework =
                """
                \tat com.example.checkout.CartService.total(CartService.java:42)
                \tat com.example.checkout.CheckoutController.submit(CheckoutController.java:88)
                \tat org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:166)
                """;

        assertThat(fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).hash())
                .isEqualTo(fingerprinter.compute(java("boom", sameAppPathDifferentFramework)).hash());
    }

    @Test
    void differentCodePathsProduceDifferentFingerprints() {
        String otherPath =
                """
                \tat com.example.billing.InvoiceService.render(InvoiceService.java:12)
                \tat com.example.billing.InvoiceController.get(InvoiceController.java:30)
                """;

        assertThat(fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).hash())
                .isNotEqualTo(fingerprinter.compute(java("boom", otherPath)).hash());
    }

    @Test
    void differentExceptionTypesProduceDifferentFingerprints() {
        GroupingInput other =
                new GroupingInput(
                        "checkout-api",
                        Platform.JAVA,
                        "java.lang.IllegalStateException",
                        "boom",
                        TRACE_BEFORE_EDIT);

        assertThat(fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).hash())
                .isNotEqualTo(fingerprinter.compute(other).hash());
    }

    @Test
    void differentServicesDoNotShareAGroup() {
        GroupingInput other =
                new GroupingInput(
                        "billing-worker",
                        Platform.JAVA,
                        "java.lang.NullPointerException",
                        "boom",
                        TRACE_BEFORE_EDIT);

        assertThat(fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).hash())
                .isNotEqualTo(fingerprinter.compute(other).hash());
    }

    @Test
    void fallsBackToTheMessageWhenThereAreNoFrames() {
        Fingerprint first = fingerprinter.compute(java("cart 41 is empty", null));
        Fingerprint second = fingerprinter.compute(java("cart 77 is empty", null));

        assertThat(first.degradedReason()).isEqualTo(Fingerprint.NO_FRAMES);
        // Normalization is what lets the message be used at all: the raw texts differ.
        assertThat(first.hash()).isEqualTo(second.hash());
    }

    @Test
    void fallsBackToVendorFramesWhenNothingIsInApp() {
        String vendorOnly =
                """
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
                \tat java.base/java.lang.Thread.run(Thread.java:1583)
                """;

        Fingerprint fingerprint = fingerprinter.compute(java("boom", vendorOnly));

        assertThat(fingerprint.degradedReason()).isEqualTo(Fingerprint.NO_IN_APP_FRAMES);
        assertThat(fingerprint.culprit())
                .isEqualTo("org.springframework.web.servlet.DispatcherServlet#doDispatch");
    }

    @Test
    void minifiedFramesAreDetectedAndNotUsedForGrouping() {
        // Minified names are reassigned on every build. Grouping on them would open a
        // fresh group per deploy, so the message is used instead.
        String buildOne =
                """
                    at t (/app/dist/bundle.min.js:1:15234)
                    at n (/app/dist/bundle.min.js:1:15901)
                    at e (/app/dist/bundle.min.js:1:16022)
                """;
        String buildTwo =
                """
                    at r (/app/dist/bundle.min.js:1:15240)
                    at o (/app/dist/bundle.min.js:1:15910)
                    at a (/app/dist/bundle.min.js:1:16040)
                """;

        GroupingInput one =
                new GroupingInput("web-ui", Platform.JAVASCRIPT, "TypeError", "x is not a function", buildOne);
        GroupingInput two =
                new GroupingInput("web-ui", Platform.JAVASCRIPT, "TypeError", "x is not a function", buildTwo);

        Fingerprint first = fingerprinter.compute(one);

        assertThat(first.degradedReason()).isEqualTo(Fingerprint.MINIFIED);
        assertThat(first.hash()).isEqualTo(fingerprinter.compute(two).hash());
    }

    @Test
    void detectsThePlatformWhenTheCallerDoesNotDeclareIt() {
        GroupingInput undeclared =
                new GroupingInput(
                        "web-ui",
                        Platform.UNKNOWN,
                        "TypeError",
                        "boom",
                        "    at resolveUser (/app/src/services/user.js:31:18)");

        assertThat(fingerprinter.compute(undeclared).platform()).isEqualTo(Platform.JAVASCRIPT);
        assertThat(fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).platform())
                .isEqualTo(Platform.JAVA);
    }

    @Test
    void recordsTheExactTextThatWasHashed() {
        Fingerprint fingerprint = fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT));

        assertThat(fingerprint.input())
                .startsWith("v1\nplatform=java\nservice=checkout-api\ntype=java.lang.NullPointerException")
                .contains("frame=com.example.checkout.CartService#total")
                .doesNotContain("InvocableHandlerMethod");
    }

    @Test
    void producesAReadableTitleAndCulprit() {
        Fingerprint fingerprint = fingerprinter.compute(java("cart 41 is empty", TRACE_BEFORE_EDIT));

        assertThat(fingerprint.title()).isEqualTo("NullPointerException: cart <num> is empty");
        assertThat(fingerprint.culprit()).isEqualTo("com.example.checkout.CartService#total");
    }

    @Test
    void hashIsStableAcrossRuns() {
        // Guards against accidentally introducing anything non-deterministic, such as a
        // hash seeded per JVM or a set iteration order leaking into the input.
        String first = fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).hash();

        for (int i = 0; i < 50; i++) {
            assertThat(fingerprinter.compute(java("boom", TRACE_BEFORE_EDIT)).hash())
                    .isEqualTo(first);
        }
        assertThat(first).hasSize(32).matches("[0-9a-f]{32}");
    }
}
