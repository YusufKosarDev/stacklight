"use strict";

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms).unref?.());

/**
 * The single loop that talks to the collector.
 *
 * Everything the application does is an enqueue. Delivery, retries and the waiting in
 * between happen here, so a collector taking a hundred seconds to wake is invisible to the
 * request that reported the error.
 */
class Dispatcher {
  constructor(queue, transport, options, log) {
    this.queue = queue;
    this.transport = transport;
    this.options = options;
    this.log = log;

    this.sentCount = 0;
    this.failedAttempts = 0;
    this.givenUpCount = 0;
    this.lastError = null;

    this.running = false;
    this.loopPromise = null;
    this.consecutiveFailures = 0;
  }

  start() {
    this.running = true;
    this.loopPromise = this.loop();
  }

  async loop() {
    while (this.running) {
      const batch = this.queue.drain(this.options.batchSize);

      if (batch.length === 0) {
        await sleep(200);
        continue;
      }

      const done = await this.deliver(batch, this.options.requestTimeoutMs);
      if (done) {
        this.consecutiveFailures = 0;
      } else {
        this.consecutiveFailures += 1;
        await sleep(this.backoffMs(this.consecutiveFailures));
      }
    }
  }

  /** @returns true when the batch was delivered or definitively rejected */
  async deliver(batch, timeoutMs) {
    let result;
    try {
      result = await this.transport.send(batch, timeoutMs);
    } catch (error) {
      // Nothing in here is allowed to end the loop. A reporter that stops reporting
      // because of its own bug is the worst outcome available.
      result = { delivered: false, retryable: true, detail: String(error) };
    }

    if (result.delivered) {
      this.sentCount += batch.length;
      return true;
    }

    // A batch is one request per event, so it can stop in the middle. What the collector
    // already took is delivered and counted; only the rest is still owed. Sending the
    // accepted part again would cost a round trip each to be told by the collector's
    // duplicate check that it already had them, and against a 429 it would feed the very
    // problem it is reacting to.
    const accepted = result.deliveredBeforeFailure ?? 0;
    if (accepted > 0) {
      this.sentCount += accepted;
    }
    const remainder = batch.slice(accepted);

    this.failedAttempts += 1;
    this.lastError = result.detail;

    if (!result.retryable) {
      this.givenUpCount += remainder.length;
      this.log(`discarding ${remainder.length} events: ${result.detail}`);
      return true;
    }

    this.queue.requeueFront(remainder);
    this.log(`requeued ${remainder.length} events: ${result.detail}`);
    return false;
  }

  /**
   * Exponential backoff with full jitter.
   *
   * The jitter is not decoration. Several instances of an application usually fail at the
   * same moment for the same reason, and a fixed schedule would have them all retry in
   * step, arriving together on a collector that is still waking up.
   */
  backoffMs(consecutiveFailures) {
    const exponent = Math.min(consecutiveFailures - 1, 30);
    const ceiling = Math.min(
      this.options.retryMaxDelayMs,
      this.options.retryBaseDelayMs * 2 ** exponent,
    );
    return Math.floor(Math.random() * (ceiling + 1));
  }

  /**
   * Sends what is queued until it is empty or time runs out.
   *
   * Bounded because a shutdown that waits on an unreachable collector is a process that
   * will not exit.
   */
  async flush(timeoutMs) {
    const deadline = Date.now() + timeoutMs;

    for (;;) {
      const remaining = deadline - Date.now();
      if (remaining <= 0) {
        break;
      }

      const batch = this.queue.drain(this.options.batchSize);
      if (batch.length === 0) {
        return;
      }

      // The attempt gets whatever is left, never more.
      const done = await this.deliver(batch, Math.min(this.options.requestTimeoutMs, remaining));
      if (!done) {
        await sleep(Math.min(200, Math.max(0, deadline - Date.now())));
      }
    }

    if (this.queue.size > 0) {
      this.log(`shutdown flush ran out of time with ${this.queue.size} events left`);
    }
  }

  async stop() {
    this.running = false;
    if (this.loopPromise) {
      await this.loopPromise;
    }
  }
}

module.exports = { Dispatcher };
