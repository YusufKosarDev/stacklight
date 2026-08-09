"use strict";

/**
 * Sends a real error to a real collector.
 *
 *   STACKLIGHT_ENDPOINT=https://your-collector/api/events \
 *   STACKLIGHT_KEY=... \
 *   node examples/node-demo/demo.js
 *
 * Nothing here is mocked. The point of this file is to be the thing that finds out
 * whether the client works against a collector that goes to sleep.
 */

const { StacklightClient } = require("../../sdk/node/src/index");

const stacklight = StacklightClient.start({
  endpoint: process.env.STACKLIGHT_ENDPOINT,
  apiKey: process.env.STACKLIGHT_KEY,
  service: "node-demo",
  release: process.env.RELEASE || "0.1.0",
  debug: true,
});

/** A plausible failure, several frames deep, with a dependency frame in the middle. */
function resolveUser(order) {
  return order.customer.id;
}

function handler(order) {
  try {
    return resolveUser(order);
  } catch (error) {
    stacklight.captureException(error);
    throw error;
  }
}

async function main() {
  console.log("endpoint:", process.env.STACKLIGHT_ENDPOINT || "(not set)");

  try {
    handler({ id: 7 });
  } catch {
    console.log("application carried on after the failure");
  }

  stacklight.captureMessage("demo run finished", "INFO");

  console.log("captured:", JSON.stringify(stacklight.stats()));

  // Wait for the background dispatcher rather than only flushing. Against a collector
  // that is waking up, the first attempts are meant to fail; what is worth showing is
  // that the events survive them and land once it answers.
  const expected = stacklight.stats().accepted;
  const deadline = Date.now() + Number(process.env.WAIT_SECONDS || 180) * 1000;
  const started = Date.now();

  while (Date.now() < deadline && stacklight.stats().sent < expected) {
    await new Promise((r) => setTimeout(r, 2000));
    const s = stacklight.stats();
    console.log(
      `  +${((Date.now() - started) / 1000).toFixed(0)}s  sent=${s.sent} queued=${s.queued} ` +
        `attempts=${s.failedAttempts} last=${s.lastError || "-"}`,
    );
  }

  const final = stacklight.stats();
  console.log("final:", JSON.stringify(final));
  console.log(
    final.sent === expected
      ? `delivered all ${expected} events after ${((Date.now() - started) / 1000).toFixed(0)}s`
      : `gave up with ${final.queued} still queued`,
  );

  await stacklight.close();
  process.exit(0);
}

main();
