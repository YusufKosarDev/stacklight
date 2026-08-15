/**
 * The front page, which is the one a stranger opens.
 *
 * Two of these are about honesty rather than function. The traffic behind this
 * deployment is written rather than reported, and the page says so; a dashboard full of
 * convincing faults that stopped saying it would be making a claim nobody intended.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import Page from "@/app/page";
import { aGroup, reset, scenario } from "./fixtures";
import { render, text } from "./render";

const open = (params: Record<string, string> = {}) =>
  Page({ searchParams: Promise.resolve(params) });

test("the page says the data is generated, not collected", async () => {
  reset();
  scenario.groups = [aGroup()];

  const page = await text(await open());

  assert.match(page, /generated scenario, not real user traffic/i);
});

test("an empty deployment renders an empty state rather than throwing", async () => {
  // The state a fork lands in on its first run, and the one every list forgets.
  reset();

  const page = await text(await open());

  assert.match(page, /Overview/);
  assert.doesNotMatch(page, /undefined|NaN/);
});

test("the status chips carry their counts and survive a filter", async () => {
  reset();
  scenario.groups = [aGroup(), aGroup({ id: 2, status: "regressed" })];
  scenario.counts = { open: 14, regressed: 1, resolved: 0, ignored: 0 };
  scenario.services = ["checkout-api", "search-indexer"];

  const page = await text(await open({ status: "open", service: "checkout-api" }));

  // The counts deliberately ignore the status filter while respecting the others: a
  // count that collapsed to the status already chosen would stop being a way to move.
  assert.match(page, /Open 14/);
  assert.match(page, /Regressed 1/);
  assert.match(page, /Resolved 0/);
});

test("the next-page link keeps the filters that were applied", async () => {
  // Paging that dropped the filter would silently widen the list under the reader.
  reset();
  scenario.groups = [aGroup()];
  scenario.nextCursor = "2026-08-13T07:23:33.123456Z|1";
  scenario.services = ["checkout-api"];

  // The markup rather than the text, because what is under test is an href.
  const html = await render(await open({ status: "open", service: "checkout-api" }));

  assert.match(html, /service=checkout-api/);
  assert.match(html, /status=open/);
  assert.match(html, /after=/);
});

test("a group row shows what it is and where it came from", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.sparklines = new Map([[1, [0, 2, 5, 1]]]);

  const page = await text(await open());

  assert.match(page, /NullPointerException/);
  assert.match(page, /com\.example\.checkout\.CartService#total/);
  assert.match(page, /checkout-api/);
});
