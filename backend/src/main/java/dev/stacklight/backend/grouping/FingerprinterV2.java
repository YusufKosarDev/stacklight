package dev.stacklight.backend.grouping;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Second grouping algorithm.
 *
 * <p>Everything {@link FingerprinterV1} decided still holds — line numbers stay out,
 * vendor frames stay out, the message is only used when frames are not available. Two
 * things change, and they pull in opposite directions on purpose.
 *
 * <h2>Fewer frames decide identity</h2>
 *
 * V1 hashed up to eight in-app frames, which makes a fingerprint a description of the
 * whole call path rather than of the fault. One bug reached through two different
 * application code paths — a helper called from two places, a handler invoked by two
 * routes — becomes two groups under v1 and one under v2. Over-splitting is the failure
 * that makes a triage list useless, because the same problem shows up as several entries
 * that each look small.
 *
 * <p>The number is three rather than one because the top frame alone is often a shared
 * utility that throws for unrelated reasons. Three keeps enough of the path to tell those
 * apart while dropping the tail that only records how the code was reached.
 *
 * <h2>A frame keeps the file it came from</h2>
 *
 * V1's frame identity is {@code declaringClass#function}, falling back to the file only
 * when there is no declaring class. For Java that is right: the class is the file. For
 * JavaScript it loses information, because the declaring class is a scope rather than a
 * location — {@code Object.<anonymous>} in two unrelated entry files produces one
 * signature under v1, and they are not the same frame.
 *
 * <p>So v2 keeps both when both exist and differ.
 *
 * <h2>Why this does not touch Frame.signature()</h2>
 *
 * {@link Frame#signature()} is what v1 hashes. Editing it would change what v1 produces
 * for events arriving tomorrow, which is exactly the silent re-pointing the version key
 * exists to prevent. V2 carries its own.
 */
@Component
public class FingerprinterV2 implements Fingerprinter {

    public static final int VERSION = 2;

    /** Enough of the path to separate faults, not enough to record how they were reached. */
    private static final int MAX_FRAMES = 3;

    /** Below this many in-app frames, the minification heuristic is not reliable. */
    private static final int MIN_FRAMES_FOR_MINIFICATION_CHECK = 3;

    private static final double MINIFIED_SHARE = 0.6;

    private final List<StackTraceParser> parsers;
    private final MessageNormalizer messageNormalizer;

    FingerprinterV2(List<StackTraceParser> parsers, MessageNormalizer messageNormalizer) {
        this.parsers = parsers;
        this.messageNormalizer = messageNormalizer;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Fingerprint compute(GroupingInput input) {
        Platform platform = resolvePlatform(input);
        List<Frame> frames = parseFrames(platform, input.stacktrace());

        List<Frame> inApp = frames.stream().filter(Frame::inApp).toList();
        String normalizedMessage = messageNormalizer.normalize(input.message());
        String type = normalizeType(input.exceptionType());

        String degradedReason = null;
        List<Frame> selected;

        if (frames.isEmpty()) {
            degradedReason = Fingerprint.NO_FRAMES;
            selected = List.of();
        } else if (inApp.isEmpty()) {
            degradedReason = Fingerprint.NO_IN_APP_FRAMES;
            selected = frames;
        } else if (looksMinified(inApp)) {
            degradedReason = Fingerprint.MINIFIED;
            selected = List.of();
        } else {
            selected = inApp;
        }

        List<String> lines = new ArrayList<>();
        lines.add("v" + VERSION);
        lines.add("platform=" + platform.wireName());
        lines.add("service=" + input.service());
        lines.add("type=" + type);

        if (selected.isEmpty()) {
            lines.add("message=" + normalizedMessage);
        } else {
            selected.stream()
                    .limit(MAX_FRAMES)
                    .forEach(frame -> lines.add("frame=" + signatureOf(frame)));
        }

        if (degradedReason != null) {
            lines.add("degraded=" + degradedReason);
        }

        String fingerprintInput = String.join("\n", lines);

        return new Fingerprint(
                hash(fingerprintInput),
                VERSION,
                fingerprintInput,
                buildTitle(type, normalizedMessage),
                buildCulprit(inApp, frames),
                degradedReason,
                platform,
                frames);
    }

    /**
     * Frame identity for v2: the location as well as the scope.
     *
     * <p>Both are kept when both exist and say different things. When the file adds
     * nothing over the declaring class — the Java case, where {@code CartService.java}
     * merely repeats {@code com.example.CartService} — it is left out, so Java
     * fingerprints stay as readable as they were.
     */
    static String signatureOf(Frame frame) {
        String scope = blankToNull(frame.declaringClass());
        String file = blankToNull(frame.file());
        String function = blankToNull(frame.function());
        String name = function == null ? "<anonymous>" : function;

        if (scope == null) {
            return (file == null ? "<unknown>" : file) + "#" + name;
        }
        if (file == null || fileRepeatsClass(file, scope)) {
            return scope + "#" + name;
        }
        return file + ":" + scope + "#" + name;
    }

    /** True when the file name is just the class's own source file, as on the JVM. */
    private static boolean fileRepeatsClass(String file, String declaringClass) {
        int dot = file.lastIndexOf('.');
        String stem = dot > 0 ? file.substring(0, dot) : file;
        // Nested and inner classes report the outer class's file.
        String outer = declaringClass.split("\\$")[0];
        return outer.equals(stem) || outer.endsWith("." + stem);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Platform resolvePlatform(GroupingInput input) {
        if (input.declaredPlatform() != null && input.declaredPlatform() != Platform.UNKNOWN) {
            return input.declaredPlatform();
        }

        Platform best = Platform.UNKNOWN;
        int bestScore = 0;
        for (StackTraceParser parser : parsers) {
            int score = parser.confidence(input.stacktrace());
            if (score > bestScore) {
                bestScore = score;
                best = parser.platform();
            }
        }
        return best;
    }

    private List<Frame> parseFrames(Platform platform, String stacktrace) {
        if (stacktrace == null || stacktrace.isBlank() || platform == Platform.UNKNOWN) {
            return List.of();
        }
        return parsers.stream()
                .filter(parser -> parser.platform() == platform)
                .findFirst()
                .map(parser -> parser.parse(stacktrace))
                .orElseGet(List::of);
    }

    private static boolean looksMinified(List<Frame> inApp) {
        if (inApp.stream().anyMatch(frame -> frame.file() != null && frame.file().contains(".min."))) {
            return true;
        }
        if (inApp.size() < MIN_FRAMES_FOR_MINIFICATION_CHECK) {
            return false;
        }

        long shortNames =
                inApp.stream()
                        .map(Frame::function)
                        .filter(name -> name != null && name.length() <= 2)
                        .count();
        return (double) shortNames / inApp.size() >= MINIFIED_SHARE;
    }

    private static String normalizeType(String exceptionType) {
        if (exceptionType == null || exceptionType.isBlank()) {
            return "<unknown>";
        }
        return exceptionType.strip().replaceAll("\\$\\$Lambda.*$", "").replaceAll("\\$\\d+$", "");
    }

    private static String buildTitle(String type, String normalizedMessage) {
        String simpleType = type;
        int lastDot = type.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < type.length() - 1) {
            simpleType = type.substring(lastDot + 1);
        }

        String title =
                normalizedMessage.isBlank() ? simpleType : simpleType + ": " + normalizedMessage;
        return title.length() <= 200 ? title : title.substring(0, 199) + "…";
    }

    private static String buildCulprit(List<Frame> inApp, List<Frame> all) {
        if (!inApp.isEmpty()) {
            return signatureOf(inApp.get(0));
        }
        if (!all.isEmpty()) {
            return signatureOf(all.get(0));
        }
        return null;
    }

    /** SHA-256 truncated to 128 bits, which is far past the point of collisions here. */
    private static String hash(String input) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(input.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[16];
            System.arraycopy(digest, 0, truncated, 0, 16);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
