"use strict";

const { normalize, isConfigured } = require("./options");
const { EventQueue } = require("./queue");
const { fromError, fromMessage } = require("./event");
const { createHttpTransport } = require("./transport");
const { Dispatcher } = require("./dispatcher");

/**
 * Reports errors to a Stacklight collector.
 *
 * ```js
 * const { StacklightClient } = require("@stacklight/client");
 *
 * const stacklight = StacklightClient.start({
 *   endpoint: "https://collector.example.com/api/events",
 *   apiKey: process.env.STACKLIGHT_KEY,
 *   service: "checkout-api",
 *   release: "2026.8.1",
 * });
 *
 * stacklight.captureException(error);
 * ```
 *
 * `captureException` enqueues and returns. It opens no connection, waits for none and
 * throws nothing: the caller is usually already dealing with something that went wrong,
 * and making that worse is not an option available to an error reporter.
 */
class StacklightClient {
  constructor(options, transport) {
    this.options = options;
    this.enabled = isConfigured(options);
    this.log = options.debug
      ? (message) => process.stderr.write(`[stacklight] ${message}\n`)
      : () => {};

    this.queue = new EventQueue(options.queueCapacity);
    this.transport = transport || createHttpTransport(options, this.log);
    this.dispatcher = new Dispatcher(this.queue, this.transport, options, this.log);
    this.handlers = [];
  }

  /** Starts a client. Without an endpoint and key it is inert rather than absent. */
  static start(input, transport) {
    const options = normalize(input);
    const client = new StacklightClient(options, transport);

    if (!client.enabled) {
      // Not an error. An application should be able to run somewhere with no collector
      // without that being a case it has to handle.
      client.log("no endpoint or key configured; capture is a no-op");
      return client;
    }

    client.dispatcher.start();
    client.installProcessHandlers();
    return client;
  }

  captureException(error, level = "ERROR") {
    if (!this.enabled || error === undefined || error === null) {
      return;
    }
    try {
      this.queue.offer(fromError(error, level, this.options));
    } catch (own) {
      // Even building the event must not propagate. Whatever went wrong here matters
      // less than the error the caller was already handling.
      this.log(`capture failed: ${own}`);
    }
  }

  captureMessage(message, level = "INFO") {
    if (!this.enabled || message === undefined || message === null || String(message).trim() === "") {
      return;
    }
    try {
      this.queue.offer(fromMessage(message, level, this.options));
    } catch (own) {
      this.log(`capture failed: ${own}`);
    }
  }

  async flush() {
    if (this.enabled) {
      await this.dispatcher.flush(this.options.shutdownTimeoutMs);
    }
  }

  stats() {
    return {
      accepted: this.queue.accepted,
      sent: this.dispatcher.sentCount,
      dropped: this.queue.dropped + this.dispatcher.givenUpCount,
      queued: this.queue.size,
      failedAttempts: this.dispatcher.failedAttempts,
      lastError: this.dispatcher.lastError,
    };
  }

  async close() {
    if (!this.enabled) {
      return;
    }
    this.removeProcessHandlers();
    await this.dispatcher.stop();
    await this.dispatcher.flush(this.options.shutdownTimeoutMs);
  }

  /**
   * Hooks the places Node surfaces failures.
   *
   * The `uncaughtException` case needs care. Registering a listener suppresses Node's
   * default behaviour of printing the error and exiting, so a reporter that simply
   * listened would silently turn a crash into a hang. The handler below restores that
   * behaviour when nothing else has claimed it: if this client is the only listener, it
   * flushes and then exits with the code Node would have used. When the application has
   * its own listener, that one is left in charge.
   */
  installProcessHandlers() {
    if (this.options.captureUncaught) {
      const onUncaught = (error) => {
        this.captureException(error, "FATAL");
        const soleListener = process.listenerCount("uncaughtException") === 1;

        this.flush()
          .catch(() => {})
          .finally(() => {
            if (soleListener) {
              process.stderr.write(`${(error && error.stack) || error}\n`);
              process.exit(1);
            }
          });
      };
      process.on("uncaughtException", onUncaught);
      this.handlers.push(["uncaughtException", onUncaught]);
    }

    if (this.options.captureUnhandledRejections) {
      const onRejection = (reason) => {
        this.captureException(reason instanceof Error ? reason : new Error(String(reason)), "ERROR");
      };
      process.on("unhandledRejection", onRejection);
      this.handlers.push(["unhandledRejection", onRejection]);
    }

    // A normal exit should not leave the last events behind, and those are usually the
    // ones worth having.
    const onBeforeExit = () => {
      this.dispatcher.flush(this.options.shutdownTimeoutMs).catch(() => {});
    };
    process.on("beforeExit", onBeforeExit);
    this.handlers.push(["beforeExit", onBeforeExit]);
  }

  removeProcessHandlers() {
    for (const [event, handler] of this.handlers) {
      process.removeListener(event, handler);
    }
    this.handlers = [];
  }
}

module.exports = { StacklightClient };
