/**
 * The scorecard, which is the page the detector argument was settled on.
 *
 * Two properties matter more than the layout. Exactly one detector may be marked active,
 * because exactly one is allowed to raise alerts; and the table is shown unfiltered,
 * including the runs where a shadow beats the detector in charge. A page that quietly
 * dropped the second would turn a comparison into an advertisement.
 */
import { test } from "node:test";
import assert from "node:assert/strict";

import Page from "@/app/detectors/page";
import { aDetector, reset, scenario } from "./fixtures";
import { text } from "./render";

/** The three as they stood at the end of the traffic run. */
function scorecard() {
  return [
    aDetector({ detector: "ewma", is_active: true, tp: 9, fp: 4, fn: 2, tn: 96, fired: 13 }),
    aDetector({ detector: "zscore", is_active: false, tp: 5, fp: 0, fn: 6, tn: 100, fired: 5 }),
    aDetector({ detector: "poisson", is_active: false, tp: 9, fp: 13, fn: 2, tn: 87, fired: 22 }),
  ];
}

test("exactly one detector is active and the rest are shadows", async () => {
  reset();
  scenario.detectors = scorecard();

  const page = await text(await Page());

  // Each label is checked against the detector it belongs to. Counting the words across
  // the page was the first attempt and it was wrong twice over: the prose above the
  // table uses both of them, so the count was never going to be one and two, and a
  // count would have passed just as happily with the labels on the wrong rows.
  assert.match(page, /ewma active/);
  assert.match(page, /zscore shadow/);
  assert.match(page, /poisson shadow/);

  assert.doesNotMatch(page, /zscore active/);
  assert.doesNotMatch(page, /poisson active/);
});

test("precision and recall are computed from the counts, not stored", async () => {
  reset();
  scenario.detectors = scorecard();

  const page = await text(await Page());

  // ewma: 9 of 13 firings held up, 9 of 11 surges caught.
  assert.match(page, /ewma active.*Precision 69%/);
  assert.match(page, /ewma active.*Recall 82%/);
});

test("a shadow that beats the active detector is shown, not hidden", async () => {
  // The property the page claims in its own words. zscore never cried wolf and ewma is
  // the one in charge; if the table only ever flattered the incumbent it would not be
  // worth building.
  reset();
  scenario.detectors = scorecard();

  const page = await text(await Page());

  assert.match(page, /zscore shadow.*Precision 100%/);
  assert.match(page, /shown unfiltered/i);
});

test("a detector nothing has judged yet does not render a divide by zero", async () => {
  // The state every scorecard starts in, and the one that produces NaN% if precision is
  // taken without checking.
  reset();
  scenario.detectors = [
    aDetector({ detector: "ewma", is_active: true, fired: 0, judged: 0, tp: 0, fp: 0, fn: 0, tn: 0 }),
  ];

  const page = await text(await Page());

  assert.doesNotMatch(page, /NaN|Infinity/);
});
