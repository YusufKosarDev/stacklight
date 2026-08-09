"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventQueue } = require("../src/queue");

const event = (id) => ({ eventId: id, service: "svc", level: "ERROR", message: "boom" });

test("delivers in the order things failed", () => {
  const queue = new EventQueue(10);
  queue.offer(event("1"));
  queue.offer(event("2"));
  queue.offer(event("3"));

  assert.deepEqual(
    queue.drain(10).map((e) => e.eventId),
    ["1", "2", "3"],
  );
});

test("at capacity the oldest gives way", () => {
  // The queue only fills when the collector cannot be reached. When it comes back, what
  // is happening now is worth more than what was happening two minutes ago.
  const queue = new EventQueue(3);
  ["1", "2", "3", "4"].forEach((id) => queue.offer(event(id)));

  assert.deepEqual(
    queue.drain(10).map((e) => e.eventId),
    ["2", "3", "4"],
  );
  assert.equal(queue.dropped, 1);
});

test("offering never throws however full it is", () => {
  const queue = new EventQueue(2);
  for (let i = 0; i < 10000; i++) {
    queue.offer(event(String(i)));
  }

  assert.equal(queue.size, 2);
  assert.equal(queue.accepted, 10000);
  assert.equal(queue.dropped, 9998);
});

test("every drop is counted", () => {
  const queue = new EventQueue(5);
  for (let i = 0; i < 12; i++) {
    queue.offer(event(String(i)));
  }

  assert.equal(queue.accepted - queue.dropped, queue.size);
});

test("a failed batch goes back where it was", () => {
  const queue = new EventQueue(10);
  ["1", "2", "3"].forEach((id) => queue.offer(event(id)));

  const batch = queue.drain(2);
  queue.offer(event("4"));
  queue.requeueFront(batch);

  assert.deepEqual(
    queue.drain(10).map((e) => e.eventId),
    ["1", "2", "3", "4"],
  );
});

test("requeueing into a full queue still respects the bound", () => {
  const queue = new EventQueue(3);
  ["1", "2"].forEach((id) => queue.offer(event(id)));
  const batch = queue.drain(2);

  ["3", "4", "5"].forEach((id) => queue.offer(event(id)));
  queue.requeueFront(batch);

  assert.equal(queue.size, 3);
  assert.deepEqual(
    queue.drain(10).map((e) => e.eventId),
    ["1", "2", "3"],
  );
});
