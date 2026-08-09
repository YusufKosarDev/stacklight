"use strict";

/**
 * Defaults chosen against a measured collector rather than picked round.
 *
 * The collector sleeps after fifteen idle minutes and takes between 95 and 114 seconds to
 * wake; one measured wake returned 503 rather than holding the connection open. The
 * timeouts are short because the first attempt against a sleeping collector is expected to
 * fail, and the backoff ceiling is well under the wake time so that several attempts span
 * it instead of one long wait sitting past it.
 */
const DEFAULTS = {
  endpoint: "",
  apiKey: "",
  service: "unknown",
  release: undefined,
  platform: "javascript",

  queueCapacity: 512,
  batchSize: 20,

  requestTimeoutMs: 5000,
  retryBaseDelayMs: 1000,
  retryMaxDelayMs: 30000,
  shutdownTimeoutMs: 3000,

  captureUncaught: true,
  captureUnhandledRejections: true,
  debug: false,
};

function normalize(input = {}) {
  const options = { ...DEFAULTS, ...input };

  options.endpoint = String(options.endpoint || "").trim();
  options.apiKey = String(options.apiKey || "").trim();
  options.service = String(options.service || "").trim() || "unknown";
  options.release = options.release ? String(options.release).trim() : undefined;

  options.queueCapacity = Math.max(1, Number(options.queueCapacity) || 1);
  options.batchSize = Math.max(1, Number(options.batchSize) || 1);

  return options;
}

/** Whether there is enough here to send anything at all. */
function isConfigured(options) {
  return Boolean(options.endpoint) && Boolean(options.apiKey);
}

module.exports = { DEFAULTS, normalize, isConfigured };
