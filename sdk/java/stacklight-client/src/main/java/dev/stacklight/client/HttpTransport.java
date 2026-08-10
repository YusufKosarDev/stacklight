package dev.stacklight.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Posts events to the collector over HTTP.
 *
 * <p>The collector takes one event per request, so a batch is a loop rather than a single
 * call. That is a deliberate limit of the current wire format: batching here still helps,
 * because the connection is reused and the retry decision is made once for the group, but
 * it is not the single round trip it would be with a bulk endpoint.
 */
final class HttpTransport implements Transport {

    private final StacklightOptions options;
    private final HttpClient client;
    private final Logger logger;

    HttpTransport(StacklightOptions options, Logger logger) {
        this.options = options;
        this.logger = logger;
        this.client =
                HttpClient.newBuilder()
                        .connectTimeout(options.connectTimeout())
                        // Redirects are not followed: a redirect from an ingest endpoint
                        // is a misconfiguration, and quietly following one could send
                        // events with their key somewhere unintended.
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
    }

    @Override
    public Result send(List<StacklightEvent> batch, Duration timeout) {
        int delivered = 0;
        for (StacklightEvent event : batch) {
            Result result = sendOne(event, timeout);
            if (!result.delivered()) {
                // Say how far it got. Everything before this event is with the collector
                // already, and reporting a bare failure would send the whole batch again.
                return result.after(delivered);
            }
            delivered++;
        }
        return Result.ok();
    }

    private Result sendOne(StacklightEvent event, Duration timeout) {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(options.endpoint()))
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("X-Stacklight-Key", options.apiKey())
                            .POST(HttpRequest.BodyPublishers.ofString(event.toJson()))
                            .build();

            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();

            if (status >= 200 && status < 300) {
                return Result.ok();
            }
            if (status == 401 || status == 403) {
                // The key is wrong. Retrying cannot fix it and would keep a bad
                // configuration alive in the queue for ever.
                return Result.reject("rejected with " + status + ", check the API key");
            }
            if (status == 400 || status == 413 || status == 422) {
                return Result.reject("rejected with " + status);
            }
            // 5xx and 429 land here. A 503 is what the platform returns while the
            // collector is still waking, which is the case this client exists to survive.
            return Result.retry("status " + status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry("interrupted");
        } catch (Exception e) {
            // Timeouts and connection failures. Expected against a sleeping collector,
            // so they are ordinary control flow here rather than something to shout about.
            logger.debug(() -> "delivery attempt failed: " + e);
            return Result.retry(e.getClass().getSimpleName());
        }
    }
}
