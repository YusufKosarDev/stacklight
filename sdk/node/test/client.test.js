"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { StacklightClient } = require("../src/index");
const { Dispatcher } = require("../src/dispatcher");
const { EventQueue } = require("../src/queue");
const { normalize } = require("../src/options");

/** A transport that can be told how to behave and remembers what it was given. */
function fakeTransport() {
  const state = {
    delivered: [],
    attempts: 0,
    failuresRemaining: 0,
    rejectEverything: false,
    delayMs: 0,
  };

  return {
    state,
    async send(batch, timeoutMs = Infinity) {
      state.attempts += 1;
      state.lastTimeoutMs = timeoutMs;
      if (state.delayMs > 0) {
        // Honours the budget it was given, the way a real request does.
        await new Promise((r) => setTimeout(r, Math.min(state.delayMs, timeoutMs)));
      }
      if (state.rejectEverything) {
        return { delivered: false, retryable: false, detail: "401" };
      }
      if (state.failuresRemaining > 0) {
        state.failuresRemaining -= 1;
        return { delivered: false, retryable: true, detail: "simulated" };
      }
      state.delivered.push(...batch);
      return { delivered: true, retryable: false, detail: null };
    },
  };
}

const options = (extra = {}) => ({
  endpoint: "https://collector.invalid/api/events",
  apiKey: "test-key",
  service: "checkout-api",
  release: "2026.8.1",
  retryBaseDelayMs: 10,
  retryMaxDelayMs: 50,
  shutdownTimeoutMs: 2000,
  captureUncaught: false,
  captureUnhandledRejections: false,
  ...extra,
});

const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return true;
    await new Promise((r) => setTimeout(r, 10));
  }
  return false;
};

test("capture returns immediately even when delivery is slow", async () => {
  // The measured cold start of the collector is between 95 and 114 seconds. If any of
  // that reached the caller, this client would be worse than no client.
  const transport = fakeTransport();
  transport.state.delayMs = 2000;
  const client = StacklightClient.start(options(), transport);

  const start = process.hrtime.bigint();
  for (let i = 0; i < 50; i++) {
    client.captureException(new Error(`boom ${i}`));
  }
  const elapsedMs = Number(process.hrtime.bigint() - start) / 1e6;

  assert.ok(elapsedMs < 100, `capture took ${elapsedMs}ms`);
  await client.close();
});

test("an event that fails twice is still delivered", async () => {
  // A single failed attempt is not a lost event; the first request against a sleeping
  // collector is expected to fail.
  const transport = fakeTransport();
  transport.state.failuresRemaining = 2;
  const client = StacklightClient.start(options(), transport);

  client.captureException(new Error("boom"));

  assert.ok(await waitFor(() => transport.state.delivered.length === 1));
  assert.equal(transport.state.attempts, 3);
  await client.close();
});

test("a rejection is not retried for ever", async () => {
  const transport = fakeTransport();
  transport.state.rejectEverything = true;
  const client = StacklightClient.start(options(), transport);

  client.captureException(new Error("boom"));

  assert.ok(await waitFor(() => client.stats().dropped === 1));
  assert.equal(transport.state.attempts, 1);
  assert.match(client.stats().lastError, /401/);
  await client.close();
});

test("a full queue drops the oldest and says so", async () => {
  const transport = fakeTransport();
  transport.state.failuresRemaining = Number.MAX_SAFE_INTEGER;
  const client = StacklightClient.start(options({ queueCapacity: 10 }), transport);

  for (let i = 0; i < 1000; i++) {
    client.captureException(new Error(`boom ${i}`));
  }

  const stats = client.stats();
  assert.equal(stats.accepted, 1000);
  assert.ok(stats.dropped > 900);
  assert.ok(stats.queued <= 10);
  await client.close();
});

test("closing sends what is still queued", async () => {
  const transport = fakeTransport();
  const client = StacklightClient.start(options(), transport);

  for (let i = 0; i < 5; i++) {
    client.captureException(new Error(`boom ${i}`));
  }
  await client.close();

  assert.equal(transport.state.delivered.length, 5);
  assert.equal(client.stats().queued, 0);
});

test("a shutdown flush gives up rather than holding the process open", async () => {
  const transport = fakeTransport();
  transport.state.failuresRemaining = Number.MAX_SAFE_INTEGER;
  const client = StacklightClient.start(options({ shutdownTimeoutMs: 300 }), transport);
  client.captureException(new Error("boom"));

  const start = Date.now();
  await client.close();

  assert.ok(Date.now() - start < 2000);
});

test("the shutdown bound holds even when each attempt is slow", async () => {
  // The bound has to be the shutdown timeout, not the shutdown timeout plus one request
  // timeout. An attempt started just inside the deadline must be given only what is left.
  const transport = fakeTransport();
  transport.state.failuresRemaining = Number.MAX_SAFE_INTEGER;
  transport.state.delayMs = 5000;
  const client = StacklightClient.start(
    options({ shutdownTimeoutMs: 500, requestTimeoutMs: 5000 }),
    transport,
  );
  client.captureException(new Error("boom"));

  const start = Date.now();
  await client.close();

  assert.ok(Date.now() - start < 1500, `close took ${Date.now() - start}ms`);
});

test("a flush attempt is given only the time that is left", async () => {
  const transport = fakeTransport();
  transport.state.failuresRemaining = Number.MAX_SAFE_INTEGER;
  const client = StacklightClient.start(
    options({ shutdownTimeoutMs: 200, requestTimeoutMs: 30000 }),
    transport,
  );
  client.captureException(new Error("boom"));
  await client.close();

  assert.ok(transport.state.lastTimeoutMs <= 200, `got ${transport.state.lastTimeoutMs}`);
});

test("with no endpoint configured the client is inert rather than broken", async () => {
  const client = StacklightClient.start({ service: "checkout-api" });

  client.captureException(new Error("boom"));
  client.captureMessage("something", "WARN");
  await client.flush();

  assert.equal(client.stats().accepted, 0);
});

test("capture never throws whatever it is given", async () => {
  const transport = fakeTransport();
  const client = StacklightClient.start(options(), transport);

  client.captureException(null);
  client.captureException(undefined);
  client.captureMessage("");
  client.captureMessage(null);
  client.captureException("a bare string");
  client.captureException({ not: "an error" });

  assert.equal(client.stats().accepted, 2);
  await client.close();
});

test("the stack is what V8 produced, untouched", async () => {
  const transport = fakeTransport();
  const client = StacklightClient.start(options(), transport);

  function inner() {
    throw new TypeError("Cannot read properties of undefined (reading 'id')");
  }

  try {
    inner();
  } catch (error) {
    client.captureException(error);
  }
  await client.close();

  const [event] = transport.state.delivered;
  assert.equal(event.exceptionType, "TypeError");
  // The collector's parser keys on this exact shape.
  assert.match(event.stacktrace, /\n\s+at inner \(.*client\.test\.js:\d+:\d+\)/);
});

test("every event carries its own id", async () => {
  const transport = fakeTransport();
  const client = StacklightClient.start(options(), transport);

  client.captureException(new Error("boom"));
  client.captureException(new Error("boom"));
  await client.close();

  const [first, second] = transport.state.delivered;
  assert.notEqual(first.eventId, second.eventId);
  assert.match(first.eventId, /^[0-9a-f-]{36}$/);
});

test("backoff grows, is capped, and is jittered", () => {
  const dispatcher = new Dispatcher(
    new EventQueue(10),
    fakeTransport(),
    normalize(options({ retryBaseDelayMs: 1000, retryMaxDelayMs: 30000 })),
    () => {},
  );

  for (let attempt = 1; attempt <= 12; attempt++) {
    const ceiling = Math.min(30000, 1000 * 2 ** Math.min(attempt - 1, 30));
    for (let sample = 0; sample < 50; sample++) {
      const delay = dispatcher.backoffMs(attempt);
      assert.ok(delay >= 0 && delay <= ceiling, `attempt ${attempt} gave ${delay}`);
    }
  }

  // Full jitter: instances failing together must not come back in step.
  const samples = new Set();
  for (let i = 0; i < 50; i++) {
    samples.add(dispatcher.backoffMs(8));
  }
  assert.ok(samples.size > 1);
});
