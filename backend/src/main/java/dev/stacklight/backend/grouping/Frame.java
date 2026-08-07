package dev.stacklight.backend.grouping;

/**
 * One line of a stack trace after parsing.
 *
 * @param module optional module or package container ({@code java.base}, a node_modules
 *     package name); null when the platform has no such concept for the frame
 * @param declaringClass fully qualified class for Java; the object or file scope for
 *     JavaScript; null when the frame is a bare function
 * @param function method or function name; null for anonymous top-level frames
 * @param file source file name as reported, without directories
 * @param line 1-based line number, or -1 when the runtime did not report one
 * @param inApp true when the frame belongs to the application rather than a runtime,
 *     framework or third-party dependency
 */
public record Frame(
        String module,
        String declaringClass,
        String function,
        String file,
        int line,
        boolean inApp) {

    /**
     * Identity of the frame for fingerprinting: everything except the line number.
     *
     * <p>Line numbers are excluded on purpose. Editing an unrelated line above a throw
     * site shifts every number below it, and a fingerprint that moved on every such edit
     * would open a new group for an error that never changed.
     */
    public String signature() {
        StringBuilder sb = new StringBuilder();
        if (declaringClass != null && !declaringClass.isBlank()) {
            sb.append(declaringClass);
        } else if (file != null && !file.isBlank()) {
            sb.append(file);
        } else {
            sb.append("<unknown>");
        }
        sb.append('#');
        sb.append(function == null || function.isBlank() ? "<anonymous>" : function);
        return sb.toString();
    }
}
