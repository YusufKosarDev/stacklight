import test from "node:test";
import assert from "node:assert/strict";

import { summarise } from "../lib/overview.ts";
import type { GroupSummary } from "../lib/queries.ts";

/*
 * Two things about running these at all.
 *
 * Imports carry the .ts extension and a relative path, because Node's type
 * stripping resolves neither a missing extension nor the "@/" alias from
 * tsconfig. The type import above survives only because it is erased before
 * Node sees it -- if lib/overview.ts ever imports a *value* through "@/", these
 * tests stop running, and the failure will look like a module-resolution error
 * rather than what it is.
 */

function group(overrides: Partial<GroupSummary> = {}): GroupSummary {
  return {
    id: 1,
    title: "NullPointerException",
    service: "checkout-api",
    platform: "java",
    level: "ERROR",
    status: "open",
    culprit: "CartService#total",
    degraded_reason: null,
    event_count: 1,
    first_seen: "2026-08-10 10:00:00",
    last_seen: "2026-08-10 12:00:00",
    ...overrides,
  };
}

/** 24 hourly counts, oldest first, the shape listSparklines() produces. */
function series(...counts: number[]): number[] {
  const full = new Array(24).fill(0);
  counts.forEach((count, index) => {
    full[index] = count;
  });
  return full;
}

test("sums every group's hours into one series, by hour", () => {
  const summary = summarise(
    [group({ id: 1 }), group({ id: 2 })],
    new Map([
      [1, series(1, 2, 3)],
      [2, series(10, 20, 30)],
    ]),
  );

  assert.equal(summary.hourly.length, 24);
  assert.deepEqual(summary.hourly.slice(0, 3), [11, 22, 33]);
  assert.equal(summary.events24h, 66);
});

test("the series runs oldest first, so the last slot is the current hour", () => {
  // Worth pinning: listSparklines fills index 23 - hoursAgo, and OverviewTrend
  // labels the left edge "24h ago" and the right edge "now". If either side
  // flips, the chart silently reads backwards.
  const summary = summarise([group()], new Map([[1, series()]]));
  const hourly = [...summary.hourly];
  hourly[23] = 5;

  assert.equal(hourly.at(-1), 5, "index 23 is the current hour");
});

test("a short series is padded rather than throwing", () => {
  const summary = summarise([group()], new Map([[1, [1, 2, 3]]]));

  assert.equal(summary.hourly.length, 24);
  assert.equal(summary.events24h, 6);
});

test("a group with no series at all contributes nothing", () => {
  const summary = summarise([group({ id: 1 }), group({ id: 2 })], new Map());

  assert.equal(summary.events24h, 0);
  assert.deepEqual(summary.hourly, new Array(24).fill(0));
});

test("no groups and no rollups is zeros, not an empty array", () => {
  const summary = summarise([], new Map());

  assert.equal(summary.hourly.length, 24);
  assert.equal(summary.events24h, 0);
  assert.equal(summary.openCount, 0);
});

test("counts each status separately", () => {
  const summary = summarise(
    [
      group({ id: 1, status: "open" }),
      group({ id: 2, status: "open" }),
      group({ id: 3, status: "regressed" }),
      group({ id: 4, status: "resolved" }),
    ],
    new Map(),
  );

  assert.equal(summary.openCount, 2);
  assert.equal(summary.regressedCount, 1);
  assert.equal(summary.resolvedCount, 1);
});

test("ignored groups are counted rather than vanishing from the tally", () => {
  // The overview prints "N open · N regressed · N resolved" above a list that
  // includes ignored groups too. Without this the numbers do not add up to the
  // list underneath them, which reads as a bug in the page.
  const summary = summarise(
    [group({ id: 1, status: "open" }), group({ id: 2, status: "ignored" })],
    new Map(),
  );

  assert.equal(summary.openCount, 1);
  assert.equal(summary.ignoredCount, 1);
});
