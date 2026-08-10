"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { createHttpTransport } = require("../src/transport");

/**
 * The transport against a real server, using the one Node already ships so the client
 * keeps its promise of dragging in nothing.
 *
 * What is worth testing here is the two judgements it makes: which status codes are worth
 * another attempt, and -- because the wire format is one event per request -- how far
 * through a batch it got before it stopped.
 */

/** Starts a collector that accepts `acceptFirst` requests, then answers `thenStatus`. */
async function collector({ acceptFirst = Infinity, thenStatus = 503 } = {}) {
  const state = { requests: 0 };

  const server = http.createServer((req, res) => {
    state.requests += 1;
    // The body has to be consumed or the client can be left waiting on backpressure.
    req.resume();
    res.writeHead(state.requests <= acceptFirst ? 202 : thenStatus).end();
  });

  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  state.port = server.address().port;
  state.close = () => new Promise((resolve) => server.close(resolve));
  return state;
}

const optionsFor = (port) => ({
  endpoint: `http://127.0.0.1:${port}/api/events`,
  apiKey: "test-key",
  requestTimeoutMs: 5000,
});

const batch = (size) =>
  Array.from({ length: size }, (_, i) => ({ eventId: `e${i}`, message: `boom ${i}` }));

test("a batch that all lands is delivered", async () => {
  const server = await collector();
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(4));

    assert.equal(result.delivered, true);
    assert.equal(server.requests, 4);
  } finally {
    await server.close();
  }
});

test("reports how far it got when the collector stops part way through", async () => {
  // The case the field exists for: three events are with the collector, the fourth is
  // not, and the dispatcher must only owe the remainder.
  const server = await collector({ acceptFirst: 3 });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(6));

    assert.equal(result.delivered, false);
    assert.equal(result.retryable, true);
    assert.equal(result.deliveredBeforeFailure, 3);
    // It stopped at the first refusal rather than pushing the rest at a collector that
    // has just said it cannot take them.
    assert.equal(server.requests, 4);
  } finally {
    await server.close();
  }
});

test("a bad key is not worth retrying", async () => {
  const server = await collector({ acceptFirst: 0, thenStatus: 401 });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(3));

    assert.equal(result.retryable, false);
    assert.equal(result.deliveredBeforeFailure, 0);
    assert.match(result.detail, /401/);
  } finally {
    await server.close();
  }
});

test("a collector still waking up is worth retrying", async () => {
  // 503 is what the platform returns while the collector is still starting, which is the
  // case this whole client exists to survive.
  const server = await collector({ acceptFirst: 0, thenStatus: 503 });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(2));

    assert.equal(result.retryable, true);
    assert.equal(result.deliveredBeforeFailure, 0);
  } finally {
    await server.close();
  }
});

test("an unreachable collector is worth retrying", async () => {
  const server = await collector();
  const port = server.port;
  await server.close();

  const transport = createHttpTransport(optionsFor(port), () => {});
  const result = await transport.send(batch(1));

  assert.equal(result.delivered, false);
  assert.equal(result.retryable, true);
});
