"use strict";

/**
 * Fills the queue against an unreachable collector, to watch the drop policy work.
 *
 *   node examples/node-demo/overflow.js
 *
 * Deliberately points at an address that does not answer. The interesting question is not
 * whether delivery fails -- it will -- but whether the application notices.
 */

const { StacklightClient } = require("../../sdk/node/src/index");

const stacklight = StacklightClient.start({
  endpoint: "http://127.0.0.1:9/api/events", // discard port: nothing listens here
  apiKey: "irrelevant",
  service: "overflow-demo",
  queueCapacity: 25,
  requestTimeoutMs: 300,
  retryBaseDelayMs: 50,
  retryMaxDelayMs: 200,
  shutdownTimeoutMs: 500,
  captureUncaught: false,
  captureUnhandledRejections: false,
});

const TOTAL = 5000;

const started = process.hrtime.bigint();
for (let i = 0; i < TOTAL; i++) {
  stacklight.captureException(new Error(`burst ${i}`));
}
const elapsedMs = Number(process.hrtime.bigint() - started) / 1e6;

const stats = stacklight.stats();
console.log(`captured ${TOTAL} errors in ${elapsedMs.toFixed(1)} ms`);
console.log(`  per capture : ${((elapsedMs / TOTAL) * 1000).toFixed(1)} microseconds`);
console.log(`  accepted    : ${stats.accepted}`);
console.log(`  dropped     : ${stats.dropped}`);
console.log(`  queued      : ${stats.queued} (capacity 25)`);
console.log(`  accounted   : ${stats.accepted === stats.dropped + stats.queued + stats.sent}`);

setTimeout(async () => {
  console.log("after retries:", JSON.stringify(stacklight.stats()));
  await stacklight.close();
  process.exit(0);
}, 1500);
