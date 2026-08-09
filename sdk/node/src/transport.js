"use strict";

/** Outcome of one delivery attempt. `retryable: false` means trying again cannot help. */
const ok = () => ({ delivered: true, retryable: false, detail: null });
const retry = (detail) => ({ delivered: false, retryable: true, detail });
const reject = (detail) => ({ delivered: false, retryable: false, detail });

/**
 * Posts events to the collector.
 *
 * The collector takes one event per request, so a batch is a loop rather than one call.
 * Batching still earns its place: the connection is reused, and the retry decision is made
 * once for the group instead of per event.
 */
function createHttpTransport(options, log) {
  return {
    /**
     * @param timeoutMs budget for this attempt. Passed in rather than read from the
     *   options so a shutdown flush can shorten it: otherwise an attempt started just
     *   inside the flush deadline runs its full request timeout past it, and the
     *   shutdown bound quietly becomes the sum of the two.
     */
    async send(batch, timeoutMs = options.requestTimeoutMs) {
      for (const event of batch) {
        const result = await sendOne(event, options, log, timeoutMs);
        if (!result.delivered) {
          return result;
        }
      }
      return ok();
    },
  };
}

async function sendOne(event, options, log, timeoutMs) {
  try {
    const response = await fetch(options.endpoint, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Stacklight-Key": options.apiKey,
      },
      body: JSON.stringify(event),
      // Short by design: the point is never to hold anything open waiting for a
      // collector that has not woken up. Fail fast, come back later.
      signal: AbortSignal.timeout(timeoutMs),
      redirect: "error",
    });

    if (response.ok) {
      return ok();
    }
    if (response.status === 401 || response.status === 403) {
      // The key is wrong. Retrying cannot fix it and would keep a bad configuration
      // alive in the queue for ever.
      return reject(`rejected with ${response.status}, check the API key`);
    }
    if (response.status === 400 || response.status === 413 || response.status === 422) {
      return reject(`rejected with ${response.status}`);
    }
    // 5xx and 429 land here. 503 is what the platform returns while the collector is
    // still waking, which is the case this client exists to survive.
    return retry(`status ${response.status}`);
  } catch (error) {
    // Timeouts and connection failures. Expected against a sleeping collector, so this is
    // ordinary control flow rather than something to shout about.
    const detail = describe(error);
    log(`delivery attempt failed: ${detail}`);
    return retry(detail);
  }
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
