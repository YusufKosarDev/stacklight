package dev.stacklight.backend.grouping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaScriptStackTraceParserTests {

    private final JavaScriptStackTraceParser parser = new JavaScriptStackTraceParser();

    private static final String NODE_TRACE =
            """
            TypeError: Cannot read properties of undefined (reading 'id')
                at resolveUser (/app/src/services/user.js:31:18)
                at async handler (/app/src/routes/checkout.js:12:22)
                at Layer.handle [as handle_request] (/app/node_modules/express/lib/router/layer.js:95:5)
                at next (/app/node_modules/express/lib/router/route.js:144:13)
                at processTicksAndRejections (node:internal/process/task_queues:95:5)
            """;

    @Test
    void parsesFunctionFileAndLine() {
        List<Frame> frames = parser.parse(NODE_TRACE);

        assertThat(frames).hasSize(5);
        assertThat(frames.get(0).function()).isEqualTo("resolveUser");
        assertThat(frames.get(0).file()).isEqualTo("src/services/user.js");
        assertThat(frames.get(0).line()).isEqualTo(31);
        assertThat(frames.get(0).inApp()).isTrue();
    }

    @Test
    void treatsDependenciesAndRuntimeInternalsAsVendor() {
        List<Frame> frames = parser.parse(NODE_TRACE);

        assertThat(frames).filteredOn(Frame::inApp).hasSize(2);
        assertThat(frames)
                .filteredOn(frame -> !frame.inApp())
                .extracting(Frame::file)
                .containsExactly(
                        "node_modules/express/lib/router/layer.js",
                        "node_modules/express/lib/router/route.js",
                        "node:internal/process/task_queues");
    }

    @Test
    void capturesTheDependencyName() {
        List<Frame> frames = parser.parse(NODE_TRACE);

        assertThat(frames.get(2).module()).isEqualTo("express");
    }

    @Test
    void stripsAsyncAndNewMarkers() {
        List<Frame> frames =
                parser.parse(
                        """
                            at async handler (/app/src/a.js:1:1)
                            at new Checkout (/app/src/checkout.js:22:11)
                        """);

        assertThat(frames.get(0).function()).isEqualTo("handler");
        assertThat(frames.get(1).function()).isEqualTo("Checkout");
    }

    @Test
    void handlesBareLocationsAndAnonymousFrames() {
        List<Frame> frames =
                parser.parse(
                        """
                            at /app/src/anon.js:3:9
                            at Array.map (<anonymous>)
                            at Object.<anonymous> (/app/index.js:1:1)
                        """);

        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).function()).isNull();
        assertThat(frames.get(0).file()).isEqualTo("src/anon.js");
        assertThat(frames.get(1).inApp()).isFalse();
        assertThat(frames.get(2).declaringClass()).isEqualTo("Object");
        assertThat(frames.get(2).function()).isEqualTo("<anonymous>");
    }

    @Test
    void handlesEsmFileUrls() {
        List<Frame> frames = parser.parse("    at file:///app/src/esm.mjs:5:11");

        assertThat(frames).hasSize(1);
        assertThat(frames.get(0).file()).isEqualTo("src/esm.mjs");
        assertThat(frames.get(0).line()).isEqualTo(5);
    }

    @Test
    void trimsHostSpecificPathPrefixes() {
        // The same build reports a different absolute prefix in a container and on a
        // laptop. Both must land in one group, or the same fault would be filed twice
        // depending on where it happened to be running.
        List<Frame> container = parser.parse("    at total (/app/src/cart.js:10:5)");
        List<Frame> laptop = parser.parse("    at total (/home/dev/work/checkout/src/cart.js:10:5)");

        assertThat(container.get(0).file()).isEqualTo("src/cart.js");
        assertThat(laptop.get(0).file()).isEqualTo("src/cart.js");
        assertThat(container.get(0).signature()).isEqualTo(laptop.get(0).signature());
    }

    @Test
    void keepsDirectoriesThatDisambiguateSameNamedFiles() {
        List<Frame> cart = parser.parse("    at load (/app/src/cart/util.js:3:1)");
        List<Frame> billing = parser.parse("    at load (/app/src/billing/util.js:3:1)");

        assertThat(cart.get(0).signature()).isNotEqualTo(billing.get(0).signature());
    }

    @Test
    void fallsBackToTheFileNameWhenNoSourceRootIsRecognisable() {
        assertThat(parser.parse("    at boot (/app/index.js:1:1)").get(0).file())
                .isEqualTo("index.js");
        assertThat(parser.parse("    at boot (/home/dev/checkout/index.js:1:1)").get(0).file())
                .isEqualTo("index.js");
    }

    @Test
    void treatsSyntheticV8LocationsAsVendor() {
        // V8 emits a location for frames that have no source file at all: "index 0" for a
        // frame inside Promise.all, "unknown location" elsewhere. They are not application
        // code, and counting them as in-app would put a frame that says nothing about this
        // particular fault into its fingerprint -- and Promise.all appears in every async
        // stack there is.
        List<Frame> frames =
                parser.parse(
                        """
                            at total (/app/src/cart.js:10:5)
                            at async Promise.all (index 0)
                            at listOnTimeout (node:internal/timers:581:17)
                        """);

        assertThat(frames).hasSize(3);
        assertThat(frames.get(0).inApp()).isTrue();
        assertThat(frames.get(1).inApp()).isFalse();
        assertThat(frames.get(2).inApp()).isFalse();
    }

    @Test
    void stillTreatsARelativeFileNameAsApplicationCode() {
        // The rule above keys on "no slash and no dot". A bare file name has a dot and
        // must stay in-app, or a stack trace with relative paths would lose every frame.
        assertThat(parser.parse("    at total (cart.js:10:5)").get(0).inApp()).isTrue();
    }

    @Test
    void recognisesItsOwnFormat() {
        assertThat(parser.confidence(NODE_TRACE)).isGreaterThan(50);
        assertThat(parser.confidence("\tat com.example.Foo.bar(Foo.java:42)")).isLessThan(50);
    }
}
