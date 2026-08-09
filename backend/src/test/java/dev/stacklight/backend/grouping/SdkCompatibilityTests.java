package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The collector parses what the SDKs actually send.
 *
 * <p>The samples below are not written by hand to match the parser. They were captured
 * from a running JVM and from Node, which is the point: the SDKs send the runtime's own
 * format untouched, so this is the real thing rather than a convenient approximation. If
 * the parser and the clients ever drift apart, it shows up here.
 */
class SdkCompatibilityTests {

    /** Captured from {@code Throwable.printStackTrace}, which is what the Java SDK sends. */
    private static final String JAVA_SDK_TRACE =
            "java.lang.IllegalStateException: checkout failed for cart 8f14e45f\n"
                    + "\tat Sample$CheckoutController.submit(Sample.java:24)\n"
                    + "\tat Sample.main(Sample.java:9)\n"
                    + "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke"
                    + "(DirectMethodHandleAccessor.java:103)\n"
                    + "\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)\n"
                    + "\tat jdk.compiler/com.sun.tools.javac.launcher.Main.execute(Main.java:484)\n"
                    + "\tat jdk.compiler/com.sun.tools.javac.launcher.Main.run(Main.java:208)\n"
                    + "Caused by: java.lang.NullPointerException: Cannot invoke \"String.length()\""
                    + " because \"promoCode\" is null\n"
                    + "\tat Sample$CartService.total(Sample.java:31)\n"
                    + "\tat Sample$CheckoutController.submit(Sample.java:22)\n"
                    + "\t... 6 more\n";

    /** Captured from {@code error.stack} in Node, which is what the JavaScript SDK sends. */
    private static final String NODE_SDK_TRACE =
            "TypeError: Cannot read properties of undefined (reading 'id')\n"
                    + "    at resolveUser (/app/src/services/user.js:31:18)\n"
                    + "    at async handler (/app/src/routes/checkout.js:12:22)\n"
                    + "    at Layer.handle [as handle_request] "
                    + "(/app/node_modules/express/lib/router/layer.js:95:5)\n"
                    + "    at processTicksAndRejections (node:internal/process/task_queues:95:5)\n";

    private final JavaStackTraceParser javaParser = new JavaStackTraceParser();
    private final JavaScriptStackTraceParser jsParser = new JavaScriptStackTraceParser();
    private final FingerprinterV1 fingerprinter =
            new FingerprinterV1(List.of(new JavaStackTraceParser(), new JavaScriptStackTraceParser()),
                    new MessageNormalizer());

    @Test
    void theJavaSdkFormatIsRecognisedWithoutBeingTold() {
        assertThat(javaParser.confidence(JAVA_SDK_TRACE)).isGreaterThan(50);
        assertThat(jsParser.confidence(JAVA_SDK_TRACE)).isLessThan(50);
    }

    @Test
    void theJavaSdkTraceYieldsTheApplicationFrames() {
        List<Frame> frames = javaParser.parse(JAVA_SDK_TRACE);

        assertThat(frames)
                .filteredOn(Frame::inApp)
                .extracting(Frame::signature)
                .containsExactly(
                        "Sample$CheckoutController#submit",
                        "Sample#main",
                        "Sample$CartService#total",
                        "Sample$CheckoutController#submit");
    }

    @Test
    void theJvmAndCompilerFramesAreTreatedAsVendor() {
        List<Frame> frames = javaParser.parse(JAVA_SDK_TRACE);

        assertThat(frames)
                .filteredOn(frame -> !frame.inApp())
                .extracting(Frame::declaringClass)
                .containsExactly(
                        "jdk.internal.reflect.DirectMethodHandleAccessor",
                        "java.lang.reflect.Method",
                        "com.sun.tools.javac.launcher.Main",
                        "com.sun.tools.javac.launcher.Main");
    }

    @Test
    void theTruncationMarkerIsNotMistakenForAFrame() {
        // printStackTrace abbreviates a shared tail as "... N more". It is not a frame,
        // and counting it as one would put a nonsense entry into the fingerprint.
        List<Frame> frames = javaParser.parse(JAVA_SDK_TRACE);

        assertThat(frames).hasSize(8);
        assertThat(frames).noneMatch(frame -> frame.signature().contains("more"));
    }

    @Test
    void theNodeSdkFormatIsRecognisedWithoutBeingTold() {
        assertThat(jsParser.confidence(NODE_SDK_TRACE)).isGreaterThan(50);
        assertThat(javaParser.confidence(NODE_SDK_TRACE)).isLessThan(50);
    }

    @Test
    void theNodeSdkTraceSeparatesApplicationFromDependencies() {
        List<Frame> frames = jsParser.parse(NODE_SDK_TRACE);

        assertThat(frames)
                .filteredOn(Frame::inApp)
                .extracting(Frame::signature)
                .containsExactly(
                        "src/services/user.js#resolveUser", "src/routes/checkout.js#handler");
        assertThat(frames).filteredOn(frame -> !frame.inApp()).hasSize(2);
    }

    @Test
    void whatTheSdksSendIsEnoughToGroupOn() {
        Fingerprint java =
                fingerprinter.compute(
                        new GroupingInput(
                                "checkout-api",
                                Platform.UNKNOWN,
                                "java.lang.IllegalStateException",
                                "checkout failed for cart 8f14e45f",
                                JAVA_SDK_TRACE));

        Fingerprint node =
                fingerprinter.compute(
                        new GroupingInput(
                                "web-ui",
                                Platform.UNKNOWN,
                                "TypeError",
                                "Cannot read properties of undefined (reading 'id')",
                                NODE_SDK_TRACE));

        // Platform detected from the trace alone, no degradation, and a culprit that names
        // application code rather than a framework.
        assertThat(java.platform()).isEqualTo(Platform.JAVA);
        assertThat(java.degradedReason()).isNull();
        assertThat(java.culprit()).isEqualTo("Sample$CheckoutController#submit");

        assertThat(node.platform()).isEqualTo(Platform.JAVASCRIPT);
        assertThat(node.degradedReason()).isNull();
        assertThat(node.culprit()).isEqualTo("src/services/user.js#resolveUser");
    }

    @Test
    void aRedeployThatShiftsLineNumbersKeepsTheSameGroup() {
        // The property the SDKs depend on: sending the raw trace is safe because the
        // collector has already decided line numbers are not identity.
        String afterEdit = JAVA_SDK_TRACE.replace("Sample.java:24", "Sample.java:41")
                .replace("Sample.java:31", "Sample.java:48");

        GroupingInput before =
                new GroupingInput("checkout-api", Platform.JAVA, "java.lang.IllegalStateException",
                        "checkout failed for cart 8f14e45f", JAVA_SDK_TRACE);
        GroupingInput after =
                new GroupingInput("checkout-api", Platform.JAVA, "java.lang.IllegalStateException",
                        "checkout failed for cart 1b9d6bcd", afterEdit);

        assertThat(fingerprinter.compute(before).hash())
                .isEqualTo(fingerprinter.compute(after).hash());
    }
}
