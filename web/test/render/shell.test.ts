/**
 * The frame every route renders into, and the two standing claims it carries.
 *
 * Both are the kind of thing that disappears in a refactor without anybody noticing,
 * because nothing breaks when they do: the page still renders, the tests still pass, and
 * the dashboard just quietly stops saying two true things about itself.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import Alerts from "@/app/alerts/page";
import Detectors from "@/app/detectors/page";
import Groups from "@/app/page";
import { reset, scenario } from "./fixtures";
import { text } from "./render";

const routes = async () => {
  reset();
  scenario.navCounts = { open_groups: 14, recent_alerts: 37 };
  return {
    groups: await text(await Groups({ searchParams: Promise.resolve({}) })),
    alerts: await text(await Alerts()),
    detectors: await text(await Detectors()),
  };
};

test("the read-path claim is on every route, not just the front page", async () => {
  const pages = await routes();

  for (const [route, page] of Object.entries(pages)) {
    assert.match(page, /Renders whether or not the ingestion service is awake/, route);
    assert.match(page, /Postgres/, route);
  }
});

test("the generated-data notice is on every route too", async () => {
  // Said everywhere rather than once on the front page: a visitor can land on any of
  // these from a link, and a dashboard of convincing faults should say what it is
  // wherever it is opened.
  const pages = await routes();

  for (const [route, page] of Object.entries(pages)) {
    assert.match(page, /Generated scenario/, route);
    assert.match(page, /Not real user traffic/, route);
  }
});

test("the nav carries its counts", async () => {
  const pages = await routes();

  assert.match(pages.groups, /Groups 14/);
  assert.match(pages.groups, /Alerts 37/);
});

test("the nav still renders when its own count query has failed", async () => {
  // loadNavCounts catches and returns null on purpose: the sidebar has to render on a
  // page whose query already failed, and during a build with no DATABASE_URL at all.
  reset();
  scenario.navCounts = null as unknown as { open_groups: number; recent_alerts: number };

  const page = await text(await Detectors());

  assert.match(page, /Groups/);
  assert.match(page, /Detectors/);
  assert.doesNotMatch(page, /undefined|NaN/);
});
