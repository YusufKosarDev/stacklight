package dev.stacklight.client;

/**
 * What the client has actually done.
 *
 * <p>Exposed because the alternative is a reporter whose own losses are invisible. If
 * events are being dropped, the application should be able to find that out from the
 * application rather than by noticing an absence on a dashboard.
 *
 * @param accepted events handed to the client
 * @param sent events the collector acknowledged
 * @param dropped events the client gave up on: discarded to make room in a full queue,
 *     or refused by the collector in a way that retrying cannot change
 * @param queued events waiting right now
 * @param failedAttempts delivery attempts that did not succeed, retries included
 * @param lastError the most recent failure detail, or null
 */
public record StacklightStats(
        long accepted,
        long sent,
        long dropped,
        int queued,
        long failedAttempts,
        String lastError) {}
