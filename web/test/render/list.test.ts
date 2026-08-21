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
 * The window: which one is drawn, and what is said when it holds nothing.
 *
 * A chart of seven zero-height bars cannot be told apart from a broken query or a
 * dashboard pointed at the wrong database, and this deployment reaches that state on
 * purpose -- its faults come from a scenario that ran once and stopped. So an empty
 * default window is widened rather than drawn, and an empty one that cannot be widened
 * explains itself.
 *
 * The distinction these turn on is between a URL that named a window and one that did
 * not. Absent means "choose for me"; `7d` means seven days, empty or not. Widening
 * under somebody who has just clicked "7 days" would give them a button that appears
 * not to work, and two of the tests below exist to catch exactly that.
 */

/** A window with something in it, sized to whichever range asked. */
const busy = (days: number) => ({
  daily: new Array(days).fill(1),
  total: days,
});

/** Events exist; whether this window holds any is up to the trend fixtures. */
const HAS_EVENTS = { event_rows: 2163 };

test("an empty default window is widened to the one that has the data", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.trend30d = busy(30);
  scenario.storage = { ...scenario.storage, ...HAS_EVENTS };

  const html = await render(await open());
  const page = await text(await open());

  // The chart is drawn, over the wider window, and the tile counts the same one.
  assert.match(page, /All events, last 30 days/);
  assert.match(html, /Events · 30d/);
  // And the reader is told they got a window they did not ask for.
  assert.match(page, /No events in the last 7d — showing 30d/);
  // The switcher marks where they ended up rather than where they started.
  const thirty = html.match(/<a[^>]*>30 days<\/a>/)?.[0] ?? "";
  assert.match(thirty, /aria-current="true"/);
});

test("asking for seven days gets seven days, empty or not", async () => {
  // The whole reason an absent range is a third state. Widening here would make
  // the "7 days" button look like it does nothing.
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.trend30d = busy(30);
  scenario.storage = { ...scenario.storage, ...HAS_EVENTS };

  const html = await render(await open({ range: "7d" }));
  const page = await text(await open({ range: "7d" }));

  assert.match(page, /No events in the last 7d/);
  assert.doesNotMatch(page, /All events, last 30 days/);
  assert.match(page, /recorded window rather than a live feed/);
  assert.match(page, /2026-08-13 07:23:33 UTC/);
  // With the wider one offered rather than taken.
  assert.match(page, /Show 30d/);
  assert.match(html, /range=30d/);

  const seven = html.match(/<a[^>]*>7 days<\/a>/)?.[0] ?? "";
  assert.match(seven, /aria-current="true"/);
});

test("the widest window, still empty, is explained rather than widened again", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.trend30d = { daily: new Array(30).fill(0), total: 0 };
  scenario.storage = { ...scenario.storage, ...HAS_EVENTS };

  const page = await text(await open());

  assert.match(page, /No events in the last 30d/);
  assert.match(page, /recorded window rather than a live feed/);
  // There is nothing wider to offer, so nothing is offered.
  assert.doesNotMatch(page, /Show 30d/);
});

test("a genuinely empty database is not called a quiet window", async () => {
  // The distinction the whole branch exists for: nothing recorded, rather than
  // nothing recorded lately. Widening here would spend a query to learn what the
  // row count already said.
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
  // Widening is still tried -- a filter with nothing this week may well have
  // something this month -- and only once the widest window is also empty does the
  // filter become the thing worth pointing at.
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.trend30d = { daily: new Array(30).fill(0), total: 0 };
  scenario.storage = { ...scenario.storage, ...HAS_EVENTS };
  scenario.services = ["checkout-api"];

  const page = await text(await open({ service: "checkout-api" }));

  assert.match(page, /match this filter/);
  // Offering a wider window would send the reader somewhere equally blank.
  assert.doesNotMatch(page, /Show 30d/);
});

test("a default window with events in it is left alone", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = busy(7);
  scenario.trend30d = busy(30);
  scenario.storage = { ...scenario.storage, ...HAS_EVENTS };

  const html = await render(await open());
  const page = await text(await open());

  assert.match(page, /All events, last 7 days/);
  assert.doesNotMatch(page, /showing 30d/);
  const seven = html.match(/<a[^>]*>7 days<\/a>/)?.[0] ?? "";
  assert.match(seven, /aria-current="true"/);
});

test("an unrecognised range is nobody's choice, so the data still picks", async () => {
  // These arrive from hand-edited URLs and stale bookmarks. `all-time` is not a
  // window this page has, so it is treated as no answer rather than as a demand.
  reset();
  scenario.groups = [aGroup()];
  scenario.trend = { daily: new Array(7).fill(0), total: 0 };
  scenario.trend30d = busy(30);
  scenario.storage = { ...scenario.storage, ...HAS_EVENTS };

  const page = await text(await open({ range: "all-time" }));

  assert.match(page, /All events, last 30 days/);
});

test("every link spells out the window it is asking for", async () => {
  // One rule: no link may quietly reset the window, and none of them may leave it
  // to be chosen again -- including the default, which is the case that would make
  // the "7 days" button look broken.
  reset();
  scenario.groups = [aGroup()];
  scenario.nextCursor = "2026-08-13T07:23:33.123456Z|1";
  scenario.trend = busy(7);
  scenario.trend30d = busy(30);
  scenario.services = ["checkout-api"];

  const html = await render(await open({ range: "7d", service: "checkout-api" }));

  // The switcher, which is where it matters most -- and which carries the filter
  // too, so that changing the window does not silently widen the list as well.
  const seven = html.match(/<a[^>]*>7 days<\/a>/)?.[0] ?? "";
  const thirty = html.match(/<a[^>]*>30 days<\/a>/)?.[0] ?? "";
  assert.match(seven, /href="\/\?service=checkout-api&amp;range=7d"/);
  assert.match(thirty, /href="\/\?service=checkout-api&amp;range=30d"/);

  // The status chips, the next-page link and the search form.
  assert.match(html, /href="\/\?service=checkout-api&amp;range=7d"/);
  assert.match(html, /range=7d&amp;after=/);
  assert.match(html, /<input type="hidden" name="range" value="7d"\/?>/);
});
