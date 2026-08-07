package dev.stacklight.backend.grouping;

/** Runtime a stack trace came from. Decides which parser and vendor list apply. */
public enum Platform {
    JAVA("java"),
    JAVASCRIPT("javascript"),
    UNKNOWN("unknown");

    private final String wireName;

    Platform(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Platform fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return switch (value.trim().toLowerCase()) {
            case "java", "jvm", "kotlin", "scala" -> JAVA;
            case "javascript", "js", "node", "nodejs", "typescript", "ts" -> JAVASCRIPT;
            default -> UNKNOWN;
        };
    }
}
