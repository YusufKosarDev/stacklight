package dev.stacklight.backend.grouping;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses the output of {@code Throwable.printStackTrace()}.
 *
 * <p>Handles the shapes the JVM actually emits:
 *
 * <pre>
 *   at com.example.CartService.total(CartService.java:42)
 *   at java.base/java.util.Objects.requireNonNull(Objects.java:233)
 *   at com.example.Foo.bar(Unknown Source)
 *   at com.example.Native.call(Native Method)
 *   at com.example.Outer$Inner.run(Outer.java:88)
 *   ... 46 common frames omitted
 * </pre>
 */
@Component
public class JavaStackTraceParser implements StackTraceParser {

    private static final Pattern FRAME =
            Pattern.compile(
                    "^\\s*at\\s+"
                            + "(?:([\\w.$]+)/)?" // optional JPMS module, e.g. java.base/
                            + "([\\w.$]+)\\." // declaring class
                            + "([\\w$<>]+)" // method, including <init> and lambda$foo$0
                            + "\\(([^)]*)\\)\\s*$"); // source location

    private static final Pattern SOURCE_WITH_LINE = Pattern.compile("^(.+?):(\\d+)$");

    /**
     * Packages treated as not belonging to the application.
     *
     * <p>A deliberately conservative list: marking an application frame as vendor loses
     * grouping signal, while the reverse only adds noise. Per-service overrides are a
     * later concern; every service here is a JVM service with a conventional layout.
     */
    private static final List<String> VENDOR_PREFIXES =
            List.of(
                    "java.",
                    "javax.",
                    "jakarta.",
                    "jdk.",
                    "sun.",
                    "com.sun.",
                    "org.springframework.",
                    "org.apache.",
                    "org.hibernate.",
                    "org.eclipse.",
                    "org.jboss.",
                    "org.slf4j.",
                    "ch.qos.logback.",
                    "com.zaxxer.",
                    "org.postgresql.",
                    "com.fasterxml.",
                    "tools.jackson.",
                    "io.netty.",
                    "reactor.",
                    "org.junit.",
                    "org.mockito.",
                    "org.testcontainers.",
                    "org.flywaydb.",
                    "kotlin.",
                    "scala.",
                    "groovy.",
                    "org.codehaus.groovy.");

    @Override
    public Platform platform() {
        return Platform.JAVA;
    }

    @Override
    public int confidence(String rawTrace) {
        if (rawTrace == null || rawTrace.isBlank()) {
            return 0;
        }

        int score = 0;
        if (rawTrace.contains(".java:")) {
            score += 50;
        }
        if (rawTrace.contains("Caused by:")) {
            score += 20;
        }
        if (rawTrace.contains("common frames omitted") || rawTrace.contains("more")) {
            score += 10;
        }
        if (FRAME.matcher(firstFrameLine(rawTrace)).matches()) {
            score += 30;
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
                // Message lines, "Caused by:" headers and "... N more" markers are not
                // frames. The causal chain is intentionally flattened: the frames of every
                // cause contribute to one trace, which is what the throw site looks like.
                continue;
            }

            String module = matcher.group(1);
            String declaringClass = matcher.group(2);
            String method = matcher.group(3);
            String source = matcher.group(4);

            String file = null;
            int lineNumber = -1;
            Matcher sourceMatcher = SOURCE_WITH_LINE.matcher(source);
            if (sourceMatcher.matches()) {
                file = sourceMatcher.group(1);
                lineNumber = Integer.parseInt(sourceMatcher.group(2));
            } else if (!source.isBlank()) {
                // "Unknown Source", "Native Method"
                file = source;
            }

            frames.add(
                    new Frame(
                            module,
                            declaringClass,
                            normalizeMethod(method),
                            file,
                            lineNumber,
                            isInApp(declaringClass)));
        }

        return frames;
    }

    /**
     * Collapses the compiler-generated suffix on lambda frames.
     *
     * <p>{@code lambda$total$3} becomes {@code lambda$total} because the trailing index
     * shifts whenever another lambda is added earlier in the same method.
     */
    private static String normalizeMethod(String method) {
        int last = method.lastIndexOf('$');
        if (last > 0 && method.startsWith("lambda$")) {
            String tail = method.substring(last + 1);
            if (tail.chars().allMatch(Character::isDigit)) {
                return method.substring(0, last);
            }
        }
        return method;
    }

    private static boolean isInApp(String declaringClass) {
        for (String prefix : VENDOR_PREFIXES) {
            if (declaringClass.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static String firstFrameLine(String rawTrace) {
        for (String line : rawTrace.split("\\R")) {
            if (line.strip().startsWith("at ")) {
                return line;
            }
        }
        return "";
    }
}
