/**
 * Runs the collector's three detectors and its scorer over the schedule, offline.
 *
 * The point is to know what the scorecard should say before the traffic is sent, so that
 * a run which produces no disagreement can be told apart from a run whose scenario was
 * never capable of producing any. It is a model of the collector, not the collector: the
 * arithmetic below is transcribed from the detectors and from the scoring statement, and
 * a change to either without a change here would make this lie.
 *
 * Deliberately not a test of the backend. It is a design tool, and the numbers it prints
 * are predictions to be checked against the live scorecard afterwards.
 *
 *   node tools/traffic/simulate.mjs
 */
import { SERVICES, HOURS, totalEvents } from "./scenario.mjs";

// Transcribed from application.yaml.
const P = {
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
function upperTail(observed, lambda) {
  if (observed <= 0) return 1;
  let term = Math.exp(-lambda);
  let cumulative = term;
  for (let k = 1; k <= observed - 1; k++) {
    term *= lambda / k;
    cumulative += term;
  }
  return Math.max(0, 1 - cumulative);
}

function verdicts(history, observed) {
  const eligible = history.length >= P.minHistoryHours && observed >= P.minObserved;

  const ewmaBase = ewmaBaseline(history);
  const ewmaScore = observed / Math.max(ewmaBase, 0.5);

  const m = mean(history);
  const sigma = Math.max(sd(history), P.zSigmaFloor);
  const zScore = (observed - m) / sigma;

  const lambda = Math.max(m, P.poissonLambdaFloor);
  const tail = upperTail(observed, lambda);

  return {
    ewma: { fired: eligible && ewmaScore > P.ewmaMultiplier, score: ewmaScore },
    zscore: { fired: eligible && zScore > P.zThreshold, score: zScore },
    poisson: { fired: eligible && tail < P.poissonProbability, score: -Math.log10(Math.max(tail, 1e-300)) },
  };
}

/**
 * The scorer's rule: an hour is a genuine surge when it stands clear of the rate either
 * side of it. Empty hours count as zero rather than being skipped, and the window never
 * reaches back past the group's first sighting -- both of which the statement in
 * SelfScoringService are explicit about, and both of which change the answer.
 */
function isSurge(counts, hour) {
  const from = Math.max(0, hour - P.historyHours);
  const to = Math.min(HOURS - 1, hour + P.scoreAfterHours);
  const around = [];
  for (let h = from; h <= to; h++) if (h !== hour) around.push(counts[h]);
  const baseline = mean(around);
  const observed = counts[hour];
  return observed >= P.minObserved && observed >= Math.max(P.minObserved, baseline * P.oracleMultiplier);
}

const box = (fired, surge) =>
  fired && surge ? "TP" : fired && !surge ? "FP" : !fired && surge ? "FN" : "TN";

const names = ["ewma", "zscore", "poisson"];
const tally = Object.fromEntries(names.map((n) => [n, { TP: 0, FP: 0, FN: 0, TN: 0 }]));
const disagreements = [];

for (const service of SERVICES) {
  const counts = Array.from({ length: HOURS }, (_, h) => service.count(h));

  for (let hour = 0; hour < HOURS; hour++) {
    const observed = counts[hour];
    if (observed < P.minObserved) continue; // below the floor: nothing is evaluated or recorded

    const history = counts.slice(Math.max(0, hour - P.historyHours), hour);
    if (history.length < P.minHistoryHours) continue;

    // A verdict is only scored once there are scoreAfterHours of hindsight behind it.
    if (hour + P.scoreAfterHours >= HOURS) continue;

    const v = verdicts(history, observed);
    const surge = isSurge(counts, hour);
    const boxes = names.map((n) => box(v[n].fired, surge));
    names.forEach((n, i) => tally[n][boxes[i]]++);

    if (new Set(boxes).size > 1) {
      disagreements.push({ service: service.name, hour, observed, surge, boxes });
    }
  }
}

const pct = (n, d) => (d === 0 ? "  n/a" : `${((100 * n) / d).toFixed(0).padStart(4)}%`);

console.log(`\nSchedule: ${HOURS} hours, ${SERVICES.length} services, ${totalEvents()} events\n`);
console.log("detector   precision  recall     TP   FP   FN   TN   judged");
for (const n of names) {
  const t = tally[n];
  const judged = t.TP + t.FP + t.FN + t.TN;
  console.log(
    `${n.padEnd(10)} ${pct(t.TP, t.TP + t.FP)}      ${pct(t.TP, t.TP + t.FN)}   ` +
      `${String(t.TP).padStart(4)} ${String(t.FP).padStart(4)} ${String(t.FN).padStart(4)} ` +
      `${String(t.TN).padStart(4)}   ${String(judged).padStart(6)}`,
  );
}

console.log(`\nHours where the detectors disagree: ${disagreements.length}`);
for (const d of disagreements) {
  console.log(
    `  h${String(d.hour).padStart(2)} ${d.service.padEnd(20)} observed=${String(d.observed).padStart(3)} ` +
      `surge=${String(d.surge).padEnd(5)} ewma=${d.boxes[0]} zscore=${d.boxes[1]} poisson=${d.boxes[2]}`,
  );
}
console.log();
