package dev.stacklight.backend.grouping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses the V8 {@code error.stack} format used by Node.js and browsers.
 *
 * <pre>
 *   at total (/app/src/cart.js:10:5)
 *   at Object.&lt;anonymous&gt; (/app/index.js:1:1)
 *   at /app/src/anon.js:3:9
 *   at processTicksAndRejections (node:internal/process/task_queues:95:5)
 *   at new Checkout (/app/src/checkout.js:22:11)
 *   at async Promise.all (index 0)
 *   at Array.map (&lt;anonymous&gt;)
 * </pre>
 */
@Component
public class JavaScriptStackTraceParser implements StackTraceParser {

    /** Either {@code at fn (location)} or the bare {@code at location} form. */
    private static final Pattern FRAME =
            Pattern.compile("^\\s*at\\s+(?:(.*?)\\s+\\((.*)\\)|(.*?))\\s*$");

    private static final Pattern LOCATION_LINE_COLUMN =
            Pattern.compile("^(.*?):(\\d+):(\\d+)$");
    private static final Pattern LOCATION_LINE = Pattern.compile("^(.*?):(\\d+)$");

    private static final Pattern NODE_MODULES = Pattern.compile(".*node_modules/([^/]+)/(.*)$");

    @Override
    public Platform platform() {
        return Platform.JAVASCRIPT;
    }

    @Override
    public int confidence(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank()) {
            return 0;
        }

        int score = 0;
        // A trailing :line:column pair is the strongest signal; the JVM never emits one.
        if (Pattern.compile(":\\d+:\\d+\\)?\\s*$", Pattern.MULTILINE).matcher(rawTrace).find()) {
            score += 60;
        }
        if (rawTrace.contains("node_modules/") || rawTrace.contains("node:internal")) {
            score += 25;
        }
        if (rawTrace.contains(".js") || rawTrace.contains(".ts") || rawTrace.contains(".mjs")) {
            score += 15;
        }
        return Math.min(score, 100);
    }

    @Override
    public List<Frame> parse(String rawTrace) {
        List<Frame> frames = new ArrayList<>();
        if (rawTrace == null || rawTrace.isBlank()) {
            return frames;
        }

        for (String line : rawTrace.split("\\R")) {
            Matcher matcher = FRAME.matcher(line);
            if (!matcher.matches()) {
                continue;
            }

            String functionPart = matcher.group(1);
            String locationPart = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            if (locationPart == null || locationPart.isBlank()) {
                continue;
            }

            String declaringClass = null;
            String function = null;
            if (functionPart != null && !functionPart.isBlank()) {
                String cleaned = stripCallPrefixes(functionPart);
                int lastDot = cleaned.lastIndexOf('.');
                if (lastDot > 0) {
                    declaringClass = cleaned.substring(0, lastDot);
                    function = cleaned.substring(lastDot + 1);
                } else {
                    function = cleaned;
                }
            }

            frames.add(parseLocation(locationPart, declaringClass, function));
        }

        return frames;
    }

    private static Frame parseLocation(String location, String declaringClass, String function) {
        String path = location;
        int lineNumber = -1;

        Matcher withColumn = LOCATION_LINE_COLUMN.matcher(location);
        Matcher withLine = LOCATION_LINE.matcher(location);
        if (withColumn.matches()) {
            path = withColumn.group(1);
            lineNumber = Integer.parseInt(withColumn.group(2));
        } else if (withLine.matches()) {
            path = withLine.group(1);
            lineNumber = Integer.parseInt(withLine.group(2));
        }

        String module = null;
        Matcher nodeModules = NODE_MODULES.matcher(path.replace('\\', '/'));
        if (nodeModules.matches()) {
            module = nodeModules.group(1);
        }

        return new Frame(
                module, declaringClass, function, normalizeFile(path), lineNumber, isInApp(path));
    }

    /**
     * Directory names that conventionally mark the root of a source tree.
     *
     * <p>Note what is absent: {@code app}. It is the usual working directory inside a
     * container, so treating it as a root would make a container path keep one segment
     * that a laptop path drops, which is exactly the asymmetry this method exists to
     * remove.
     */
    private static final List<String> SOURCE_ROOTS =
            List.of("src", "dist", "lib", "build", "out", "pages", "components", "server");

    /**
     * Trims the machine-specific part of a path.
     *
     * <p>The same build reports {@code /app/src/cart.js} in a container and
     * {@code /home/someone/work/checkout/src/cart.js} on a laptop. Cutting at the last
     * source-root segment leaves {@code src/cart.js} in both cases, so one fault stays in
     * one group across environments while {@code a/util.js} and {@code b/util.js} remain
     * distinguishable. Paths with no recognisable root fall back to the file name, which
     * is the most that can be said about them without guessing.
     */
    private static String normalizeFile(String path) {
        String cleaned = path.replace('\\', '/');
        if (cleaned.startsWith("file://")) {
            cleaned = cleaned.substring("file://".length());
        }

        Matcher nodeModules = NODE_MODULES.matcher(cleaned);
        if (nodeModules.matches()) {
            return "node_modules/" + nodeModules.group(1) + "/" + nodeModules.group(2);
        }
        if (cleaned.startsWith("node:") || !cleaned.contains("/")) {
            return cleaned;
        }

        String[] segments = cleaned.split("/");
        int rootIndex = -1;
        for (int i = 0; i < segments.length - 1; i++) {
            if (SOURCE_ROOTS.contains(segments[i])) {
                rootIndex = i;
            }
        }

        if (rootIndex < 0) {
            return segments[segments.length - 1];
        }
        return String.join("/", List.of(segments).subList(rootIndex, segments.length));
    }

    private static boolean isInApp(String path) {
        String cleaned = path.replace('\\', '/');
        if (cleaned.contains("node_modules/")
                || cleaned.startsWith("node:")
                || cleaned.startsWith("internal/")
                || cleaned.equals("native")
                || cleaned.startsWith("<anonymous>")) {
            return false;
        }

        // V8 also emits locations for frames that have no source file behind them at all:
        // "index 0" inside a Promise.all, "unknown location" elsewhere. Without a slash or
        // a dot there is no path and no file name, so there is nothing here that belongs
        // to the application -- and Promise.all turns up in every async stack there is, so
        // letting it through would add a frame that says nothing about the fault to the
        // fingerprint of a great many of them.
        return cleaned.contains("/") || cleaned.contains(".");
    }

    /** Removes the {@code new } and {@code async } markers V8 prepends to a call site. */
    private static String stripCallPrefixes(String functionPart) {
        String cleaned = functionPart.strip();
        if (cleaned.startsWith("async ")) {
            cleaned = cleaned.substring("async ".length());
        }
        if (cleaned.startsWith("new ")) {
            cleaned = cleaned.substring("new ".length());
        }
        return cleaned.strip();
    }
}
