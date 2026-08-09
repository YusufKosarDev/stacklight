package dev.stacklight.client;

import java.util.function.Supplier;

/**
 * Diagnostics for the reporter itself.
 *
 * <p>Silent unless asked. A library that reports errors must not become a source of them,
 * and printing to somebody's log because delivery is slow is a small version of exactly
 * that. When enabled it goes to stderr rather than picking a logging framework, which
 * would mean a dependency this library refuses to have.
 */
final class Logger {

    private final boolean enabled;

    Logger(boolean enabled) {
        this.enabled = enabled;
    }

    void debug(Supplier<String> message) {
        if (enabled) {
            System.err.println("[stacklight] " + message.get());
        }
    }
}
