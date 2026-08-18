"use strict";

/**
 * Outcome of one delivery attempt.
 *
 * `delivered` is about the request, not the events: it says the collector answered and the
 * body was understood. What happened to each event is in the three fields below, because a
 * batch can be partly accepted and the parts mean different things.
 *
 * - `accepted` -- events the collector took, including ones it already had
 * - `discarded` -- events it refused for a reason retrying cannot change
 * - `pending`   -- events still owed, to go back on the queue
 *
 * `retryable: false` on a failed attempt means the same thing it always did: trying again
 * cannot help.
 *
 * There is no longer a `deliveredBeforeFailure`. It existed because the wire format was one
 * event per request, so a batch of twenty was twenty requests that could stop at the
 * seventh -- and re-sending the first six would have spent a round trip each to be told by
 * the collector's duplicate check that it already had them. A batch is one request now.
 * Either it happened, and the collector said what became of every event in it, or it did
 * not, and the whole batch is still owed at the cost of one round trip rather than twenty.
 */
const ok = (accepted, discarded = [], pending = []) => ({
  delivered: true,
  retryable: false,
  detail: null,
  accepted,
  discarded,
  pending,
});

const retry = (detail, pending) => ({
  delivered: false,
  retryable: true,
  detail,
  accepted: 0,
  discarded: [],
  pending,
});

const reject = (detail, pending) => ({
  delivered: false,
  retryable: false,
  detail,
  accepted: 0,
  discarded: [],
  pending,
});

/**
 * Posts events to the collector.
 *
 * One request for the whole batch, against `<endpoint>/batch`. The single-event endpoint is
 * still there and still works; this client stopped using it because twenty events used to
 * mean twenty round trips, twenty connections and twenty follow-up passes on a collector
 * that takes a hundred seconds to wake.
 */
function createHttpTransport(options, log) {
  const batchEndpoint = `${options.endpoint.replace(/\/+$/, "")}/batch`;

  return {
    /**
     * @param timeoutMs budget for this attempt. Passed in rather than read from the
     *   options so a shutdown flush can shorten it: otherwise an attempt started just
     *   inside the flush deadline runs its full request timeout past it, and the
     *   shutdown bound quietly becomes the sum of the two.
     */
    async send(batch, timeoutMs = options.requestTimeoutMs) {
      let response;
      try {
        response = await fetch(batchEndpoint, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Stacklight-Key": options.apiKey,
          },
          body: JSON.stringify(batch),
          // Short by design: the point is never to hold anything open waiting for a
          // collector that has not woken up. Fail fast, come back later.
          signal: AbortSignal.timeout(timeoutMs),
          redirect: "error",
        });
      } catch (error) {
        // Timeouts and connection failures. Expected against a sleeping collector, so this
        // is ordinary control flow rather than something to shout about.
        const detail = describe(error);
        log(`delivery attempt failed: ${detail}`);
        return retry(detail, batch);
      }

      if (response.status === 401 || response.status === 403) {
        // The key is wrong. Retrying cannot fix it and would keep a bad configuration
        // alive in the queue for ever.
        return reject(`rejected with ${response.status}, check the API key`, batch);
      }
      if (response.status === 400 || response.status === 413 || response.status === 422) {
        // The envelope was wrong: empty, oversized, or not what the collector parses.
        return reject(`rejected with ${response.status}`, batch);
      }
      if (!response.ok) {
        // 5xx and 429 land here. 503 is what the platform returns while the collector is
        // still waking, which is the case this client exists to survive.
        return retry(`status ${response.status}`, batch);
      }

      return await split(batch, response, log);
    },
  };
}

/**
 * Sorts a 202 into what was taken, what was refused, and what is still owed.
 *
 * A body that cannot be read is treated as everything having been accepted rather than as a
 * failure. The collector answered 2xx, so the events are with it; re-sending them because
 * this client could not parse the receipt would be the one behaviour worse than losing the
 * receipt.
 */
async function split(batch, response, log) {
  let body;
  try {
    body = await response.json();
  } catch {
    log("batch accepted but its result could not be read");
    return ok(batch.length);
  }

  const results = Array.isArray(body && body.results) ? body.results : null;
  if (!results || results.length !== batch.length) {
    log("batch accepted but its result did not describe every event");
    return ok(batch.length);
  }

  const discarded = [];
  const pending = [];
  let accepted = 0;

  results.forEach((result, index) => {
    if (!result || !result.error) {
      accepted += 1;
      return;
    }
    if (result.retryable) {
      pending.push(batch[index]);
    } else {
      discarded.push({ event: batch[index], reason: result.error });
    }
  });

  return ok(accepted, discarded, pending);
}

/**
 * A usable description of a failed fetch.
 *
 * Node wraps connection failures in a bare `TypeError` and puts the part worth reading --
 * ECONNREFUSED, ENOTFOUND -- on `cause`. Reporting only the outer name would leave the
 * client's own diagnostics saying "TypeError" for every network problem it ever has.
 */
function describe(error) {
  if (!error) {
    return "network error";
  }
  if (error.name === "TimeoutError" || error.name === "AbortError") {
    return "timeout";
  }
  const code = error.cause && (error.cause.code || error.cause.message);
  return code ? `${error.name}: ${code}` : error.name || "network error";
}

module.exports = { createHttpTransport, ok, retry, reject };
