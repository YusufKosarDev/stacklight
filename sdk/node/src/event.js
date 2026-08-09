"use strict";

const { randomUUID } = require("node:crypto");

const MAX_MESSAGE = 4000;
const MAX_STACKTRACE = 20000;

function truncate(value, max) {
  if (typeof value !== "string" || value.length <= max) {
    return value;
  }
  return value.slice(0, max);
}

/**
 * Builds the payload the collector accepts.
 *
 * `eventId` is generated here rather than server-side so that a delivery retried after an
 * ambiguous failure is recognised as the same event instead of counted twice.
 *
 * The stack is `error.stack` untouched. The collector's parser was written against the V8
 * format -- `at fn (/path/file.js:12:5)`, `node_modules/`, `node:internal` -- so sending
 * anything tidier would mean two formats to keep in step instead of one.
 */
function fromError(error, level, options) {
  const isError = error instanceof Error;
  const message = isError
    ? error.message || error.name || "Error"
    : String(error);

  return {
    eventId: randomUUID(),
    service: options.service,
    level,
    message: truncate(message, MAX_MESSAGE),
    platform: options.platform,
    exceptionType: isError ? error.name || "Error" : typeof error,
    stacktrace: isError && error.stack ? truncate(error.stack, MAX_STACKTRACE) : undefined,
    release: options.release,
  };
}

function fromMessage(message, level, options) {
  return {
    eventId: randomUUID(),
    service: options.service,
    level,
    message: truncate(String(message), MAX_MESSAGE),
    platform: options.platform,
    release: options.release,
  };
}

module.exports = { fromError, fromMessage };
