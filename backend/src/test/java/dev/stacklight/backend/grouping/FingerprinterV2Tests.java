package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** What v2 changes, and what it must not. */
class FingerprinterV2Tests {

    private final MessageNormalizer normalizer = new MessageNormalizer();
    private final List<StackTraceParser> parsers =
            List.of(new JavaStackTraceParser(), new JavaScriptStackTraceParser());

    private final FingerprinterV1 v1 = new FingerprinterV1(parsers, normalizer);
    private final FingerprinterV2 v2 = new FingerprinterV2(parsers, normalizer);

    private static GroupingInput java(String stacktrace) {
        return new GroupingInput(
                "checkout-api",
                Platform.JAVA,
                "java.lang.IllegalStateException",
                "could not price the cart",
                stacktrace);
    }

    private static GroupingInput javascript(String stacktrace) {
        return new GroupingInput(
                "web-ui", Platform.JAVASCRIPT, "TypeError", "x is not a function", stacktrace);
    }

    // --- the version key ------------------------------------------------------

    @Test
    void v2ReportsItsOwnVersion() {
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.compute(java("\tat com.example.A.run(A.java:1)\n")).version()).isEqualTo(2);
    }

    @Test
    void v1IsUntouchedByV2Existing() {
        // The whole point of keying a group by (fingerprint, version) is that an old
        // version keeps producing exactly what it always produced. If v2 had changed
        // Frame.signature() -- the obvious place to put its rule -- this would fail.
        String trace = "\tat com.example.checkout.CartService.total(CartService.java:42)\n";

        assertThat(v1.compute(java(trace)).hash())
                .isEqualTo(v1.compute(java(trace)).hash());
        assertThat(v1.compute(java(trace)).input()).startsWith("v1\n");
        assertThat(v1.compute(java(trace)).input())
                .contains("frame=com.example.checkout.CartService#total");
    }

    // --- fewer frames decide identity ----------------------------------------

    @Test
    void oneFaultReachedThroughTwoPathsBecomesOneGroup() {
        // The merge v2 exists for. Same throw site, same three frames above it, then
        // different callers further down -- one bug, two entry points.
        String viaCheckout =
                """
                \tat com.example.CartService.total(CartService.java:42)
                \tat com.example.CartService.price(CartService.java:20)
                \tat com.example.Checkout.submit(Checkout.java:88)
                \tat com.example.CheckoutRoute.post(CheckoutRoute.java:12)
                \tat com.example.Router.dispatch(Router.java:5)
                """;
        String viaAdmin =
                """
                \tat com.example.CartService.total(CartService.java:42)
                \tat com.example.CartService.price(CartService.java:20)
                \tat com.example.Checkout.submit(Checkout.java:88)
                \tat com.example.AdminTools.recalculate(AdminTools.java:31)
                \tat com.example.AdminRoute.get(AdminRoute.java:9)
                """;

        assertThat(v2.compute(java(viaCheckout)).hash())
                .isEqualTo(v2.compute(java(viaAdmin)).hash());

        // And v1 splits them, which is the behaviour being changed.
        assertThat(v1.compute(java(viaCheckout)).hash())
                .isNotEqualTo(v1.compute(java(viaAdmin)).hash());
    }

    @Test
    void differentFaultsInTheSameTopFrameStayApart() {
        // Three frames rather than one, because the top frame is often a shared helper.
        String fromCart =
                """
                \tat com.example.Util.require(Util.java:9)
                \tat com.example.CartService.total(CartService.java:42)
                \tat com.example.Checkout.submit(Checkout.java:88)
                """;
        String fromBilling =
                """
                \tat com.example.Util.require(Util.java:9)
                \tat com.example.BillingService.charge(BillingService.java:17)
                \tat com.example.Invoice.send(Invoice.java:60)
                """;

        assertThat(v2.compute(java(fromCart)).hash())
                .isNotEqualTo(v2.compute(java(fromBilling)).hash());
    }

    // --- a frame keeps the file it came from ----------------------------------

    @Test
    void twoJavaScriptEntryFilesNoLongerCollide() {
        // Under v1 both are "Object#<anonymous>" and the file is thrown away, so two
        // unrelated entry points share a frame signature.
        String fromIndex = "    at Object.<anonymous> (/app/src/index.js:1:1)\n";
        String fromWorker = "    at Object.<anonymous> (/app/src/worker.js:1:1)\n";

        assertThat(v1.compute(javascript(fromIndex)).hash())
                .isEqualTo(v1.compute(javascript(fromWorker)).hash());

        assertThat(v2.compute(javascript(fromIndex)).hash())
                .isNotEqualTo(v2.compute(javascript(fromWorker)).hash());
    }

    @Test
    void aJavaFrameDoesNotRepeatItsOwnSourceFile() {
        // CartService.java says nothing that com.example.CartService has not already
        // said, so v2 leaves it out and the input stays readable.
        Fingerprint result =
                v2.compute(java("\tat com.example.CartService.total(CartService.java:42)\n"));

        assertThat(result.input()).contains("frame=com.example.CartService#total");
        assertThat(result.input()).doesNotContain("CartService.java");
    }

    @Test
    void anInnerClassStillCountsAsItsOuterFile() {
        Fingerprint result =
                v2.compute(
                        java("\tat com.example.Checkout$CartService.total(Checkout.java:42)\n"));

        assertThat(result.input()).contains("frame=com.example.Checkout$CartService#total");
        assertThat(result.input()).doesNotContain("Checkout.java:");
    }

    @Test
    void aJavaScriptFrameWithNoScopeKeepsUsingItsFile() {
        Fingerprint result = v2.compute(javascript("    at total (/app/src/cart.js:10:5)\n"));

        assertThat(result.input()).contains("frame=src/cart.js#total");
    }

    // --- everything v1 established, still true --------------------------------

    @Test
    void lineNumbersStillDoNotMatter() {
        assertThat(v2.compute(java("\tat com.example.A.run(A.java:1)\n")).hash())
                .isEqualTo(v2.compute(java("\tat com.example.A.run(A.java:99)\n")).hash());
    }

    @Test
    void vendorFramesStillDoNotDecideTheGroup() {
        String throughSpring =
                """
                \tat com.example.A.run(A.java:1)
                \tat org.springframework.web.method.InvocableHandlerMethod.doInvoke(X.java:1)
                """;
        String throughCatalina =
                """
                \tat com.example.A.run(A.java:1)
                \tat org.apache.catalina.core.ApplicationFilterChain.doFilter(Y.java:1)
                """;

        assertThat(v2.compute(java(throughSpring)).hash())
                .isEqualTo(v2.compute(java(throughCatalina)).hash());
    }

    @Test
    void anEventWithNoTraceFallsBackToTheMessageAndSaysSo() {
        Fingerprint result = v2.compute(java(null));

        assertThat(result.degradedReason()).isEqualTo(Fingerprint.NO_FRAMES);
        assertThat(result.input()).contains("message=could not price the cart");
    }

    @Test
    void minifiedFramesAreStillDetectedRatherThanGroupedOn() {
        String minified =
                """
                    at t (/app/dist/bundle.min.js:1:100)
                    at n (/app/dist/bundle.min.js:1:200)
                    at e (/app/dist/bundle.min.js:1:300)
                """;

        assertThat(v2.compute(javascript(minified)).degradedReason())
                .isEqualTo(Fingerprint.MINIFIED);
    }

    @Test
    void theSameEventAlwaysHashesTheSame() {
        String trace = "\tat com.example.A.run(A.java:1)\n";

        assertThat(v2.compute(java(trace)).hash()).isEqualTo(v2.compute(java(trace)).hash());
    }
}
