/**
 * The alerts page, where a row's delivery state is the only place a reader learns
 * whether anything actually left the building.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import Page from "@/app/alerts/page";
import { anAlert, reset, scenario } from "./fixtures";
import { text } from "./render";

test("every delivery state gets a label a reader can act on", async () => {
  reset();
  scenario.alerts = [
    anAlert({ id: 1, delivery_state: "sent", delivery_attempts: 1 }),
    anAlert({ id: 2, delivery_state: "pending", delivery_attempts: 0 }),
    anAlert({ id: 3, delivery_state: "failed", delivery_attempts: 5, last_error: "timeout" }),
    anAlert({ id: 4, delivery_state: "disabled", delivery_attempts: 0 }),
  ];

  const page = await text(await Page());

  assert.match(page, /emailed/);
  assert.match(page, /queued/);
  assert.match(page, /delivery gave up/);

  // Past tense, and deliberately. The state is never revisited -- configuring mail
  // later must not deliver a backlog -- so a row raised weeks ago should not claim
  // anything about how the deployment is configured now.
  assert.match(page, /mail was not configured at the time/);
});

test("a silence alert reads as an absence rather than a spike", async () => {
  // The one kind raised by an event not arriving. It carries no observed count, and a
  // page that rendered it like a spike would print an empty comparison.
  reset();
  scenario.alerts = [
    anAlert({
      kind: "silence",
      detector: null,
      observed: null,
      baseline: null,
      score: null,
      service: "session-store",
    }),
  ];

  const page = await text(await Page());

  assert.match(page, /went quiet/i);
  assert.match(page, /session-store/);
  assert.doesNotMatch(page, /null|undefined|NaN/);
});

test("no alerts renders an empty state rather than a bare heading", async () => {
  reset();

  const page = await text(await Page());

  assert.match(page, /Alerts/);
  assert.doesNotMatch(page, /undefined|NaN/);
});
