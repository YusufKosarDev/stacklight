/**
 * The collector's three detectors and its scoring rule, transcribed.
 *
 * Shared by the two things that need to agree: the prediction made from the designed
 * schedule, and the check made afterwards against the hours that were actually
 * delivered. Keeping one copy is the point -- two transcriptions would drift, and the
 * whole comparison rests on both sides doing the same arithmetic.
 *
 * It is a model of the collector, not the collector. A change to a detector or to the
 * scoring statement without a change here would make everything downstream of it lie.
 */

/** Transcribed from application.yaml. */
export const P = {
  historyHours: 24,
  minHistoryHours: 6,
  minObserved: 5,
  ewmaAlpha: 0.3,
  ewmaMultiplier: 3.0,
  zThreshold: 3.0,
  zSigmaFloor: 1.0,
  poissonProbability: 0.001,
  poissonLambdaFloor: 0.1,
  scoreAfterHours: 3,
  oracleMultiplier: 4.0,
};

export const DETECTORS = ["ewma", "zscore", "poisson"];

const mean = (a) => (a.length ? a.reduce((s, x) => s + x, 0) / a.length : 0);
const sd = (a) => {
  if (!a.length) return 0;
  const m = mean(a);
  return Math.sqrt(a.reduce((s, x) => s + (x - m) ** 2, 0) / a.length);
};

function ewmaBaseline(history) {
  if (!history.length) return 0;
  let value = history[0];
  for (let i = 1; i < history.length; i++) {
    value = P.ewmaAlpha * history[i] + (1 - P.ewmaAlpha) * value;
  }
  return value;
}

/** P(X >= observed) for X ~ Poisson(lambda), accumulated the way the collector does it. */
export function upperTail(observed, lambda) {
  if (observed <= 0) return 1;
  let term = Math.exp(-lambda);
  let cumulative = term;
  for (let k = 1; k <= observed - 1; k++) {
    term *= lambda / k;
    cumulative += term;
  }
  return Math.max(0, 1 - cumulative);
}

export function verdicts(history, observed) {
  const eligible = history.length >= P.minHistoryHours && observed >= P.minObserved;

  const ewmaScore = observed / Math.max(ewmaBaseline(history), 0.5);
  const m = mean(history);
  const zScore = (observed - m) / Math.max(sd(history), P.zSigmaFloor);
  const tail = upperTail(observed, Math.max(m, P.poissonLambdaFloor));

  return {
    ewma: { fired: eligible && ewmaScore > P.ewmaMultiplier, score: ewmaScore },
    zscore: { fired: eligible && zScore > P.zThreshold, score: zScore },
    poisson: {
      fired: eligible && tail < P.poissonProbability,
      score: -Math.log10(Math.max(tail, 1e-300)),
    },
  };
}

/**
 * The scorer's rule: an hour is a genuine surge when it stands clear of the rate either
 * side of it. Empty hours count as zero rather than being skipped, and the window never
 * reaches back past the group's first sighting -- both of which the scoring statement is
 * explicit about, and both of which change the answer.
 */
export function isSurge(counts, hour, firstHour = 0) {
  const from = Math.max(firstHour, hour - P.historyHours);
  const to = Math.min(counts.length - 1, hour + P.scoreAfterHours);
  const around = [];
  for (let h = from; h <= to; h++) if (h !== hour) around.push(counts[h] ?? 0);
  const observed = counts[hour] ?? 0;
  return (
    observed >= P.minObserved &&
    observed >= Math.max(P.minObserved, mean(around) * P.oracleMultiplier)
  );
}

export const box = (fired, surge) =>
  fired && surge ? "TP" : fired && !surge ? "FP" : !fired && surge ? "FN" : "TN";

/**
 * Walks one service's hours and boxes every verdict that would have been recorded.
 *
 * Skips what the collector skips: hours under the floor are never evaluated, hours
 * without enough history behind them cannot fire, and verdicts too recent to have
 * hindsight are not scored. Getting those three exclusions wrong is the easiest way to
 * produce a tally that looks like the live one but counts different things.
 */
export function tallyService(counts, { horizon = counts.length } = {}) {
  const tally = Object.fromEntries(DETECTORS.map((d) => [d, { TP: 0, FP: 0, FN: 0, TN: 0 }]));
  const disagreements = [];
  const found = counts.findIndex((c) => c > 0);
  const firstHour = found < 0 ? 0 : found;

  for (let hour = 0; hour < counts.length; hour++) {
    const observed = counts[hour] ?? 0;
    if (observed < P.minObserved) continue;

    // The window starts at the group's first sighting, never earlier. Counting hours
    // from before a group existed as zero would drag every baseline toward nothing --
    // which the collector's own history query is explicit about, and which decides
    // whether a young group has the six hours it needs to be judged at all.
    const history = counts
      .slice(Math.max(firstHour, hour - P.historyHours), hour)
      .map((c) => c ?? 0);

    // Not skipped when the history is too short to judge. The collector records every
    // evaluation it makes, and a group without enough history behind it produces a
    // verdict of "did not fire" rather than no verdict at all -- which the scorer then
    // boxes as a true negative, or a false one if the hour turns out to have been a
    // surge. Dropping those here counted barely half of what the scorecard holds, and
    // flattered every detector's recall by hiding the misses it makes while young.
    if (hour + P.scoreAfterHours >= horizon) continue;

    const v = verdicts(history, observed);
    const surge = isSurge(counts, hour, firstHour < 0 ? 0 : firstHour);
    const boxes = DETECTORS.map((d) => box(v[d].fired, surge));
    DETECTORS.forEach((d, i) => tally[d][boxes[i]]++);

    if (new Set(boxes).size > 1) disagreements.push({ hour, observed, surge, boxes });
  }

  return { tally, disagreements };
}

export function merge(tallies) {
  const total = Object.fromEntries(DETECTORS.map((d) => [d, { TP: 0, FP: 0, FN: 0, TN: 0 }]));
  for (const t of tallies) {
    for (const d of DETECTORS) for (const k of ["TP", "FP", "FN", "TN"]) total[d][k] += t[d][k];
  }
  return total;
}

export const pct = (n, d) => (d === 0 ? "  n/a" : `${((100 * n) / d).toFixed(0).padStart(4)}%`);

export function report(title, tally) {
  console.log(`\n${title}`);
  console.log("detector   precision  recall     TP   FP   FN   TN   judged");
  for (const d of DETECTORS) {
    const t = tally[d];
    const judged = t.TP + t.FP + t.FN + t.TN;
    console.log(
      `${d.padEnd(10)} ${pct(t.TP, t.TP + t.FP)}      ${pct(t.TP, t.TP + t.FN)}   ` +
        `${String(t.TP).padStart(4)} ${String(t.FP).padStart(4)} ${String(t.FN).padStart(4)} ` +
        `${String(t.TN).padStart(4)}   ${String(judged).padStart(6)}`,
    );
  }
}
