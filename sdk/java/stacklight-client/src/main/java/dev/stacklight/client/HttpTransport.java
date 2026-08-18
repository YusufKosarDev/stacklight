package dev.stacklight.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Posts events to the collector over HTTP.
 *
 * <p>One request for the whole batch, against {@code <endpoint>/batch}. The single-event
 * endpoint is still there and still works; this client stopped using it because twenty
 * events used to mean twenty round trips, twenty connections and twenty follow-up passes on
 * a collector that takes a hundred seconds to wake.
 */
final class HttpTransport implements Transport {

    private final StacklightOptions options;
    private final URI batchEndpoint;
    private final HttpClient client;
    private final Logger logger;

    HttpTransport(StacklightOptions options, Logger logger) {
        this.options = options;
        this.logger = logger;
        this.batchEndpoint = URI.create(options.endpoint().replaceAll("/+$", "") + "/batch");
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
        try {
            String body =
                    batch.stream()
                            .map(StacklightEvent::toJson)
                            .collect(Collectors.joining(",", "[", "]"));

            HttpRequest request =
                    HttpRequest.newBuilder(batchEndpoint)
                            .timeout(timeout)
                            .header("Content-Type", "application/json")
                            .header("X-Stacklight-Key", options.apiKey())
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();

            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 401 || status == 403) {
                // The key is wrong. Retrying cannot fix it and would keep a bad
                // configuration alive in the queue for ever.
                return Result.reject("rejected with " + status + ", check the API key", batch);
            }
            if (status == 400 || status == 413 || status == 422) {
                // The envelope was wrong: empty, oversized, or not what the collector parses.
                return Result.reject("rejected with " + status, batch);
            }
            if (status < 200 || status >= 300) {
                // 5xx and 429 land here. A 503 is what the platform returns while the
                // collector is still waking, which is the case this client exists to survive.
                return Result.retry("status " + status, batch);
            }

            return split(batch, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.retry("interrupted", batch);
        } catch (Exception e) {
            // Timeouts and connection failures. Expected against a sleeping collector,
            // so they are ordinary control flow here rather than something to shout about.
            logger.debug(() -> "delivery attempt failed: " + e);
            return Result.retry(e.getClass().getSimpleName(), batch);
        }
    }

    /**
     * Sorts a 2xx into what was taken, what was refused, and what is still owed.
     *
     * <p>A receipt that cannot be read is treated as everything having been accepted. The
     * collector answered 2xx, so the events are with it; re-sending them because this client
     * could not parse the receipt would be the one behaviour worse than losing the receipt.
     */
    private Result split(List<StacklightEvent> batch, String body) {
        List<BatchReceipt.Entry> entries = BatchReceipt.parse(body);

        if (entries.size() != batch.size()) {
            if (!entries.isEmpty()) {
                logger.debug(() -> "batch receipt did not describe every event");
            }
            return Result.ok(batch.size());
        }

        List<Discarded> discarded = new ArrayList<>();
        List<StacklightEvent> pending = new ArrayList<>();
        int accepted = 0;

        for (int i = 0; i < entries.size(); i++) {
            BatchReceipt.Entry entry = entries.get(i);
            if (!entry.failed()) {
                accepted++;
            } else if (entry.retryable()) {
                pending.add(batch.get(i));
            } else {
                discarded.add(new Discarded(batch.get(i), "refused by the collector"));
            }
        }

        return Result.ok(accepted, discarded, pending);
    }
}
