import { test } from "node:test";
import assert from "node:assert/strict";

import { HOURS, SERVICES, START_ISO, hourIndex, plan, series, totalEvents } from "./scenario.mjs";

const at = (hoursAfterStart) =>
  new Date(new Date(START_ISO).getTime() + hoursAfterStart * 3_600_000);

test("the schedule has not begun before its start", () => {
  assert.equal(hourIndex(at(-0.1)), null);
  assert.equal(hourIndex(at(-50)), null);
});

test("the schedule stops itself, which is what keeps it off the hosting quota", () => {
  // The driver runs on a plain hourly cron with no end date, so this is the only thing
  // standing between a finished experiment and a service woken every hour for ever.
  assert.equal(hourIndex(at(HOURS - 0.01)), HOURS - 1);
  assert.equal(hourIndex(at(HOURS)), null);
  assert.equal(hourIndex(at(HOURS + 100)), null);
  assert.deepEqual(plan(null), []);
  assert.deepEqual(plan(HOURS), []);
});

test("an hour maps to the hour it is in, not the one it is near", () => {
  assert.equal(hourIndex(at(0)), 0);
  assert.equal(hourIndex(at(0.99)), 0);
  assert.equal(hourIndex(at(1)), 1);
  assert.equal(hourIndex(at(7.5)), 7);
});

test("the same hour always plans the same work", () => {
  // A missed run must leave a gap rather than shifting everything after it, which is
  // only true while the plan is a function of the hour and of nothing else.
  for (const hour of [0, 6, 13, 29]) {
    assert.deepEqual(
      plan(hour).map((w) => [w.service.name, w.count]),
      plan(hour).map((w) => [w.service.name, w.count]),
    );
  }
});

test("no hour reaches the collector's per-group hourly cap", () => {
  // Past 200 an hour a group keeps being counted but stops storing detail. Crossing it
  // would make the scenario measure the cap rather than the detectors.
  for (const service of SERVICES) {
    for (let hour = 0; hour < HOURS; hour++) {
      assert.ok(
        service.count(hour) < 200,
        `${service.name} hour ${hour} is ${service.count(hour)}, at or past the cap`,
      );
    }
  }
});

test("every count is a non-negative whole number of events", () => {
  for (const service of SERVICES) {
    for (let hour = 0; hour < HOURS; hour++) {
      const count = service.count(hour);
      assert.ok(Number.isInteger(count) && count >= 0, `${service.name} hour ${hour}: ${count}`);
    }
  }
});

test("the whole run stays far inside the storage budget", () => {
  // The free database plan suspends the project rather than billing for an overrun, so
  // the ceiling is the one number in this file that ends the deployment if it is wrong.
  const total = totalEvents();
  assert.ok(total > 1500, `only ${total} events; too thin to fill a scorecard`);
  assert.ok(total < 6000, `${total} events is more than this experiment needs`);
});

test("both stack parsers are exercised", () => {
  const platforms = new Set(SERVICES.map((s) => s.platform));
  assert.deepEqual([...platforms].sort(), ["java", "javascript"]);
});

test("each service carries a distinct fault, so each gets its own group", () => {
  const types = SERVICES.map((s) => s.exceptionType);
  assert.equal(new Set(types).size, types.length);
});

test("the flat profile is flat enough to collapse the z-score onto its floor", () => {
  // The sigma floor is 1.0. This profile only produces its false positive while the
  // observed spread stays under it.
  const counts = series("checkout-api").filter((c) => c < 15);
  const mean = counts.reduce((a, b) => a + b, 0) / counts.length;
  const sd = Math.sqrt(counts.reduce((s, c) => s + (c - mean) ** 2, 0) / counts.length);
  assert.ok(sd < 1.0, `ordinary hours vary by ${sd.toFixed(2)}, too much for the floor to bite`);
});

test("the bursty profile is idle far more often than it is busy", () => {
  // What desensitises the z-score is the spread, and the spread comes from the idle hours.
  const counts = series("search-indexer");
  const idle = counts.filter((c) => c === 0).length;
  assert.ok(idle > counts.length / 2, `only ${idle} idle hours of ${counts.length}`);
});

test("the service that dies stays dead long enough for the silence rule to see it", () => {
  // Six busy hours before the quiet period and none during it, with the quiet period
  // being the last three hours the rule looks at.
  const counts = series("session-store");
  const busy = counts.slice(0, 12).filter((c) => c > 0).length;
  assert.ok(busy >= 6, `only ${busy} busy hours before it goes quiet`);
  assert.deepEqual(counts.slice(12, 24), new Array(12).fill(0));
});

test("the control profile spikes hard enough that missing it would be a fault", () => {
  const counts = series("payments-api");
  const ordinary = counts.filter((c) => c < 20);
  const mean = ordinary.reduce((a, b) => a + b, 0) / ordinary.length;
  const spikes = counts.filter((c) => c >= 20);
  assert.equal(spikes.length, 2);
  for (const spike of spikes) {
    assert.ok(spike > mean * 4, `spike of ${spike} is not clear of a baseline of ${mean}`);
  }
});
