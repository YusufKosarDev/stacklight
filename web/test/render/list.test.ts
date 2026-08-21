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

/*
 * The window, and what the page says when it is empty.
 *
 * A chart of seven zero-height bars cannot be told apart from a broken query or a
 * dashboard pointed at the wrong database, and this deployment reaches that state on
 * purpose: its faults come from a scenario that ran once and stopped. These four are
 * about the page distinguishing the cases rather than drawing all of them the same.
 */

test("an empty window says so, and says when the last event arrived", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  // The events are still there; it is the last seven days that hold none of them.
  scenario.storage = { ...scenario.storage, event_rows: 2163 };

  const page = await text(await open());

  assert.match(page, /No events in the last 7d/);
  assert.match(page, /recorded window rather than a live feed/);
  assert.match(page, /2026-08-13 07:23:33 UTC/);
});

test("an empty window offers the wider one that still has the data", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.storage = { ...scenario.storage, event_rows: 2163 };

  const html = await render(await open());

  assert.match(html, /range=30d/);
});

test("a genuinely empty database is not called a quiet window", async () => {
  // The distinction the whole branch exists for: nothing recorded, rather than
  // nothing recorded lately. Offering to widen the window here would be a lie --
  // there is no wider window with anything in it.
  //
  // `event_rows` is set explicitly because the shared storage fixture is not as
  // empty as its name suggests: it carries the row counts of a deployment that
  // has been used, which is the right default for every other test here.
  reset();
  scenario.storage = { ...scenario.storage, event_rows: 0, newest_event: null };

  const page = await text(await open());

  assert.doesNotMatch(page, /recorded window rather than a live feed/);
  assert.doesNotMatch(page, /No events in the last/);
});

test("an empty window under a filter blames the filter, not the clock", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.storage = { ...scenario.storage, event_rows: 2163 };
  scenario.services = ["checkout-api"];

  const page = await text(await open({ service: "checkout-api" }));

  assert.match(page, /match this filter/);
  // Widening the window cannot help when a filter is what emptied it.
  assert.doesNotMatch(page, /Show 30 days/);
});

test("the range switcher marks the window in force", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(30).fill(1), total: 30 };

  const html = await render(await open({ range: "30d" }));

  // Each anchor is picked out by its own label and then asked about itself, so the
  // assertion does not depend on the order React happens to write attributes in --
  // and, unlike a bare search for `aria-current`, it fails if the mark lands on the
  // wrong one of the two.
  const thirty = html.match(/<a[^>]*>30 days<\/a>/)?.[0] ?? "";
  const seven = html.match(/<a[^>]*>7 days<\/a>/)?.[0] ?? "";

  assert.match(thirty, /aria-current="true"/);
  assert.doesNotMatch(seven, /aria-current/);

  // And the tile above the chart counts the same window the chart draws.
  assert.match(html, /Events · 30d/);
});

test("an unrecognised range falls back to the default rather than emptying the page", async () => {
  // These arrive from hand-edited URLs and stale bookmarks.
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: [1, 2, 3, 4, 5, 6, 7], total: 28 };

  const page = await text(await open({ range: "all-time" }));

  assert.match(page, /last 7 days/);
});

test("the range survives a filter, a search and a page turn", async () => {
  // Three links, one rule: none of them may quietly reset the window. The GET form
  // carries it as a hidden field for the same reason the status chip does.
  reset();
  scenario.groups = [aGroup()];
  scenario.nextCursor = "2026-08-13T07:23:33.123456Z|1";
  scenario.trend = { daily: new Array(30).fill(1), total: 30 };
  scenario.services = ["checkout-api"];

  const html = await render(await open({ range: "30d", service: "checkout-api" }));

  // The status chips.
  assert.match(html, /href="\/\?service=checkout-api&amp;range=30d"/);
  // The next-page link.
  assert.match(html, /range=30d&amp;after=/);
  // The search form.
  assert.match(html, /<input type="hidden" name="range" value="30d"\/?>/);
});
