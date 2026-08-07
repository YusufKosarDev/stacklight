package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaStackTraceParserTests {

    private final JavaStackTraceParser parser = new JavaStackTraceParser();

    private static final String NPE_WITH_CAUSE =
            """
            java.lang.NullPointerException: Cannot invoke "String.length()" because "s" is null
            \tat com.example.checkout.CartService.total(CartService.java:42)
            \tat com.example.checkout.CheckoutController.submit(CheckoutController.java:88)
            \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
            \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
            \tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)
            Caused by: java.lang.IllegalStateException: cart is empty
            \tat com.example.checkout.CartService.validate(CartService.java:19)
            \t... 46 common frames omitted
            """;

    @Test
    void parsesApplicationAndVendorFrames() {
        List<Frame> frames = parser.parse(NPE_WITH_CAUSE);

        assertThat(frames).hasSize(6);
        assertThat(frames.get(0).declaringClass()).isEqualTo("com.example.checkout.CartService");
        assertThat(frames.get(0).function()).isEqualTo("total");
        assertThat(frames.get(0).file()).isEqualTo("CartService.java");
        assertThat(frames.get(0).line()).isEqualTo(42);
        assertThat(frames.get(0).inApp()).isTrue();
    }

    @Test
    void marksRuntimeAndFrameworkFramesAsVendor() {
        List<Frame> frames = parser.parse(NPE_WITH_CAUSE);

        assertThat(frames)
                .filteredOn(frame -> !frame.inApp())
                .extracting(Frame::declaringClass)
                .containsExactly(
                        "jdk.internal.reflect.DirectMethodHandleAccessor",
                        "java.lang.reflect.Method",
                        "org.springframework.web.method.support.InvocableHandlerMethod");
    }

    @Test
    void flattensFramesFromTheCausalChain() {
        List<Frame> frames = parser.parse(NPE_WITH_CAUSE);

        assertThat(frames)
                .filteredOn(Frame::inApp)
                .extracting(Frame::signature)
                .containsExactly(
                        "com.example.checkout.CartService#total",
                        "com.example.checkout.CheckoutController#submit",
                        "com.example.checkout.CartService#validate");
    }

    @Test
    void readsJpmsModulePrefix() {
        List<Frame> frames = parser.parse(NPE_WITH_CAUSE);

        assertThat(frames.get(2).module()).isEqualTo("java.base");
    }

    @Test
    void handlesFramesWithoutLineNumbers() {
        List<Frame> frames =
                parser.parse(
                        """
                        \tat com.example.Generated.run(Unknown Source)
                        \tat com.example.Bridge.call(Native Method)
                        """);

        assertThat(frames).hasSize(2);
        assertThat(frames.get(0).file()).isEqualTo("Unknown Source");
        assertThat(frames.get(0).line()).isEqualTo(-1);
        assertThat(frames.get(1).file()).isEqualTo("Native Method");
    }

    @Test
    void handlesInnerClassesConstructorsAndLambdas() {
        List<Frame> frames =
                parser.parse(
                        """
                        \tat com.example.Outer$Inner.run(Outer.java:88)
                        \tat com.example.Service.<init>(Service.java:12)
                        \tat com.example.Service.lambda$total$3(Service.java:30)
                        """);

        assertThat(frames).extracting(Frame::declaringClass)
                .containsExactly("com.example.Outer$Inner", "com.example.Service", "com.example.Service");
        assertThat(frames.get(1).function()).isEqualTo("<init>");
        // The trailing lambda index shifts when another lambda is added earlier in the
        // same method, so it is dropped.
        assertThat(frames.get(2).function()).isEqualTo("lambda$total");
    }

    @Test
    void ignoresNonFrameLines() {
        List<Frame> frames =
                parser.parse(
                        """
                        java.lang.RuntimeException: boom
                        Caused by: java.io.IOException: disk full
                        \t... 12 common frames omitted
                        """);

        assertThat(frames).isEmpty();
    }

    @Test
    void recognisesItsOwnFormat() {
        assertThat(parser.confidence(NPE_WITH_CAUSE)).isGreaterThan(50);
        assertThat(parser.confidence("    at handler (/app/src/index.js:12:22)")).isLessThan(50);
        assertThat(parser.confidence(null)).isZero();
    }
}
