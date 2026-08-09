package dev.stacklight.spring;

import dev.stacklight.client.StacklightClient;

/**
 * Reports exceptions that end a thread, then hands them to whoever was handling them
 * before.
 *
 * <p>The default uncaught handler is process-wide, so installing one is taking something
 * that belonged to the application. Delegating to the previous handler is what keeps that
 * from being a change in behaviour: whatever was printing or logging those exceptions
 * still does.
 */
public class StacklightUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    private final StacklightClient client;
    private final Thread.UncaughtExceptionHandler delegate;

    public StacklightUncaughtExceptionHandler(
            StacklightClient client, Thread.UncaughtExceptionHandler delegate) {
        this.client = client;
        this.delegate = delegate;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            client.capture(throwable);
            // The thread is ending and the process may follow it. Give the event a chance
            // to leave before that happens; the flush is bounded, so this cannot hang.
            client.flush();
        } catch (RuntimeException e) {
            // Reporting must not add a second failure on top of the one being reported.
        }

        if (delegate != null) {
            delegate.uncaughtException(thread, throwable);
        } else {
            // What the JVM would have done anyway.
            System.err.print("Exception in thread \"" + thread.getName() + "\" ");
            throwable.printStackTrace();
        }
    }
}
