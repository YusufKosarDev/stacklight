"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { createHttpTransport } = require("../src/transport");

/**
 * The transport against a real server, using the one Node already ships so the client
 * keeps its promise of dragging in nothing.
 *
 * The judgements worth testing are which status codes deserve another attempt, and how a
 * partly-accepted batch is sorted: the collector answers 202 and says per event what
 * became of it, so "the request worked" and "every event landed" are no longer the same
 * question.
 */

/**
 * A collector that answers the batch endpoint.
 *
 * @param status answered instead of 202 when set
 * @param mark called with each event's index; return an object to fail that event
 * @param body raw body to answer with, for the case where the receipt cannot be read
 */
async function collector({ status = 202, mark = () => null, body = null } = {}) {
  const state = { requests: 0, paths: [], sizes: [] };

  const server = http.createServer((req, res) => {
    state.requests += 1;
    state.paths.push(req.url);

    let raw = "";
    req.on("data", (chunk) => (raw += chunk));
    req.on("end", () => {
      if (status !== 202) {
        res.writeHead(status).end();
        return;
      }

      const events = JSON.parse(raw || "[]");
      state.sizes.push(events.length);

      if (body !== null) {
        res.writeHead(202, { "Content-Type": "application/json" }).end(body);
        return;
      }

      const results = events.map((event, index) => {
        const failure = mark(index);
        return failure
          ? { eventId: event.eventId, stored: false, ...failure }
          : { eventId: event.eventId, stored: true, error: null, retryable: false };
      });

      res.writeHead(202, { "Content-Type": "application/json" }).end(
        JSON.stringify({ accepted: events.length, results }),
      );
    });
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

test("a whole batch goes in one request", async () => {
  // The point of the endpoint. Six events used to be six round trips.
  const server = await collector();
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(6));

    assert.equal(result.delivered, true);
    assert.equal(result.accepted, 6);
    assert.equal(server.requests, 1);
    assert.deepEqual(server.sizes, [6]);
    assert.deepEqual(server.paths, ["/api/events/batch"]);
  } finally {
    await server.close();
  }
});

test("an event the collector could not take yet comes back to be requeued", async () => {
  const server = await collector({
    mark: (i) => (i === 2 ? { error: "database unavailable", retryable: true } : null),
  });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(5));

    // The request worked. Only one event is still owed.
    assert.equal(result.delivered, true);
    assert.equal(result.accepted, 4);
    assert.equal(result.pending.length, 1);
    assert.equal(result.pending[0].eventId, "e2");
    assert.equal(result.discarded.length, 0);
  } finally {
    await server.close();
  }
});

test("an event the collector refused outright is discarded rather than retried", async () => {
  const server = await collector({
    mark: (i) => (i === 1 ? { error: "service must not be blank", retryable: false } : null),
  });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(3));

    assert.equal(result.delivered, true);
    assert.equal(result.accepted, 2);
    assert.equal(result.pending.length, 0);
    assert.equal(result.discarded.length, 1);
    assert.equal(result.discarded[0].event.eventId, "e1");
    assert.match(result.discarded[0].reason, /blank/);
  } finally {
    await server.close();
  }
});

test("a duplicate counts as accepted, because the collector has it", async () => {
  const server = await collector({
    mark: (i) => (i === 0 ? { stored: false, error: null, retryable: false } : null),
  });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(2));

    assert.equal(result.accepted, 2);
    assert.equal(result.pending.length, 0);
    assert.equal(result.discarded.length, 0);
  } finally {
    await server.close();
  }
});

test("a receipt that cannot be read is treated as accepted, not as failure", async () => {
  // The collector answered 2xx, so the events are with it. Re-sending them because this
  // client could not parse the receipt would be worse than losing the receipt.
  const server = await collector({ body: "not json" });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(3));

    assert.equal(result.delivered, true);
    assert.equal(result.accepted, 3);
    assert.equal(result.pending.length, 0);
  } finally {
    await server.close();
  }
});

test("a bad key is not worth retrying", async () => {
  const server = await collector({ status: 401 });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(3));

    assert.equal(result.retryable, false);
    assert.equal(result.accepted, 0);
    assert.equal(result.pending.length, 3);
    assert.match(result.detail, /401/);
  } finally {
    await server.close();
  }
});

test("a batch the collector will not parse is not worth retrying", async () => {
  const server = await collector({ status: 400 });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(2));

    assert.equal(result.retryable, false);
    assert.match(result.detail, /400/);
  } finally {
    await server.close();
  }
});

test("a collector still waking up is worth retrying", async () => {
  // 503 is what the platform returns while the collector is still starting, which is the
  // case this whole client exists to survive.
  const server = await collector({ status: 503 });
  try {
    const transport = createHttpTransport(optionsFor(server.port), () => {});
    const result = await transport.send(batch(2));

    assert.equal(result.retryable, true);
    assert.equal(result.accepted, 0);
    assert.equal(result.pending.length, 2);
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
  assert.equal(result.pending.length, 1);
});
