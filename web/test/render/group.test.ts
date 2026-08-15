/**
 * The group page is where the grouping argument is either shown or merely claimed.
 *
 * Everything else in this project can be checked without a browser: the fingerprinter
 * has its own suite, the queries have their shapes. What had nothing behind it was the
 * page that has to explain a decision to somebody -- which frames counted, what was
 * hashed, and what the answer was when the usual signal was missing. Those are the parts
 * a restyle or a refactor can quietly drop while every other test stays green.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import GroupPage from "@/app/groups/[id]/page";
import { aDetail, reset, scenario } from "./fixtures";
import { text } from "./render";

const open = (id = "1", range?: string) =>
  GroupPage({
    params: Promise.resolve({ id }),
    searchParams: Promise.resolve(range ? { range } : {}),
  });

test("in-app frames are marked apart from vendor ones, and counted", async () => {
  reset();
  scenario.detail = aDetail();

  const page = await text(await open());

  // Two of the three fixture frames belong to the application. If that sentence stops
  // matching the frames beside it, the page is explaining a different event.
  assert.match(page, /3 frames parsed, 2 belong to the application/);

  // Each label sits against its own frame. Written tightly on purpose: the first draft
  // of this test allowed anything between the two, and passed by matching the label
  // here against the fingerprint input further down the page -- which would have gone
  // on passing with the frame list deleted entirely.
  assert.match(page, /in-app com\.example\.checkout\.CartService\.total CartService\.java:88/);
  assert.match(
    page,
    /in-app com\.example\.checkout\.CheckoutController\.submit CheckoutController\.java:54/,
  );
  assert.match(
    page,
    /vendor org\.springframework\.web\.servlet\.DispatcherServlet\.doDispatch/,
  );
});

test("the fingerprint input that was hashed is on the page", async () => {
  reset();
  scenario.detail = aDetail();

  const page = await text(await open());

  assert.match(page, /com\.example\.checkout\.CartService#total/);
  assert.match(page, /b2b9c15ea9557bba93353505c471e919/);
});

test("an event with no frames says why it grouped anyway", async () => {
  // The degraded case. A page that rendered an empty panel here would be the one place
  // somebody looks to find out why a group looks wrong.
  reset();
  scenario.detail = aDetail({
    frames: [],
    culprit: null,
    degraded_reason: "no_frames",
    title: "<unknown>: Queue lag above threshold",
  });

  const page = await text(await open());

  assert.match(page, /No frames were parsed from this event/);
  assert.doesNotMatch(page, /frames parsed, \d+ belong to the application/);
  assert.match(page, /no_frames/);
});

test("a sampled group says the trend is complete and the traces are not", async () => {
  // The hourly cap keeps counting while it stops storing detail, and a chart that did
  // not say so would read as a dip exactly where the incident is worst.
  reset();
  scenario.detail = aDetail({ sampled_count: 120 });

  const page = await text(await open());

  assert.match(page, /120 of these events were counted in the trend/);
  assert.match(page, /chart below is complete/);
});

test("a group id that does not exist does not render a broken page", async () => {
  // getGroup returns null and the page is expected to hand over to notFound() rather
  // than render a shell around nothing.
  reset();
  scenario.detail = null;

  await assert.rejects(async () => text(await open("404")));
});
