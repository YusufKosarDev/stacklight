/**
 * The three-way comparison the run exists to make.
 *
 *   1. what the designed schedule predicted, committed before any traffic was sent
 *   2. what the schedule that was actually delivered would predict, from the rollups
 *   3. what the collector's own scorecard says
 *
 * The second column is the one that matters, and the reason it exists is that the
 * schedule was not delivered as designed: the scheduler dropped hours, and one hour was
 * sent twice before the reconciliation was in place. Judging the detectors against a
 * prediction made from traffic they never saw would be scoring them on the wrong exam.
 *
 * The gap between the first two columns measures how badly the schedule was holed. The
 * gap between the last two measures whether this model of the collector is faithful --
 * they should agree closely, and where they do not, this file is the more likely to be
 * wrong.
 *
 *   DATABASE_URL=... node tools/traffic/compare.mjs
 */
import { readFileSync } from "node:fs";
import { SERVICES, HOURS, START_ISO } from "./scenario.mjs";
import { DETECTORS, merge, report, tallyService } from "./detectors.mjs";

const databaseUrl =
  process.env.DATABASE_URL ??
  (() => {
    try {
      return readFileSync(new URL("../../.env.local", import.meta.url), "utf8")
        .match(/^DATABASE_URL=(.*)$/m)[1]
        .trim()
        .replace(/^["']|["']$/g, "");
    } catch {
      return null;
    }
  })();

if (!databaseUrl) {
  console.error("DATABASE_URL is not set");
  process.exit(1);
}

const api = `https://${new URL(databaseUrl).host.replace(/^[^.]+\./, "api.")}/sql`;

async function query(sql) {
  const response = await fetch(api, {
    method: "POST",
    headers: {
      "Neon-Connection-String": databaseUrl,
      "Neon-Raw-Text-Output": "true",
      "Neon-Array-Mode": "true",
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ query: sql, params: [] }),
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return (await response.json()).rows;
}

const start = new Date(START_ISO).getTime();
const hourOf = (iso) => Math.round((new Date(iso).getTime() - start) / 3_600_000);

// ---- 1. what was actually delivered, by service and schedule hour -----------------

const rollups = await query(`
  select g.service, r.bucket_start, sum(r.event_count)::int
    from event_rollups r
    join event_groups g on g.id = r.group_id
   where r.bucket_start >= timestamptz '${START_ISO}'
   group by g.service, r.bucket_start
   order by r.bucket_start`);

const delivered = new Map(SERVICES.map((s) => [s.name, new Array(HOURS).fill(0)]));
for (const [service, bucket, count] of rollups) {
  const hour = hourOf(bucket);
  if (hour >= 0 && hour < HOURS && delivered.has(service)) {
    delivered.get(service)[hour] = Number(count);
  }
}

// ---- 2. how far the delivered schedule drifted from the designed one --------------

let intact = 0;
let short = 0;
let over = 0;
const damage = [];

for (let hour = 0; hour < HOURS; hour++) {
  let state = "intact";
  const notes = [];
  for (const service of SERVICES) {
    const want = service.count(hour);
    const got = delivered.get(service.name)[hour];
    if (want === got) continue;
    notes.push(`${service.name} ${got}/${want}`);
    state = got > want ? "over" : "short";
  }
  if (state === "intact") intact++;
  else {
    if (state === "over") over++;
    else short++;
    damage.push({ hour, state, notes });
  }
}

// ---- 3. re-run the model over what was delivered ----------------------------------

/**
 * How far the collector's scorer can have got by now.
 *
 * A verdict is only scored once its bucket is scoreAfterHours old, so comparing the model
 * against the live scorecard while the run is in progress means stopping the model at the
 * same place. Without this the model counts hours the collector has not judged yet and
 * the two columns disagree for a reason that has nothing to do with either.
 */
const elapsed = Math.floor((Date.now() - start) / 3_600_000);
const scoringHorizon = Math.min(HOURS, elapsed + 1);

const designedTallies = [];
const deliveredTallies = [];
const deliveredDisagreements = [];

for (const service of SERVICES) {
  designedTallies.push(
    tallyService(
      Array.from({ length: HOURS }, (_, h) => service.count(h)),
      { horizon: HOURS },
    ).tally,
  );

  const actual = delivered.get(service.name);
  const result = tallyService(actual, { horizon: scoringHorizon });
  deliveredTallies.push(result.tally);
  for (const d of result.disagreements) {
    deliveredDisagreements.push({ service: service.name, ...d });
  }
}

// ---- 4. what the collector's own scorecard says -----------------------------------

const scored = await query(`
  select detector, outcome, count(*)::int
    from detector_observations
   where bucket_start >= timestamptz '${START_ISO}'
     and outcome is not null
   group by detector, outcome`);

const live = Object.fromEntries(DETECTORS.map((d) => [d, { TP: 0, FP: 0, FN: 0, TN: 0 }]));
const nameOf = {
  true_positive: "TP",
  false_positive: "FP",
  false_negative: "FN",
  true_negative: "TN",
};
for (const [detector, outcome, count] of scored) {
  if (live[detector] && nameOf[outcome]) live[detector][nameOf[outcome]] += Number(count);
}

const pending = await query(`
  select count(*)::int from detector_observations
   where bucket_start >= timestamptz '${START_ISO}' and outcome is null`);

// ---- 5. print --------------------------------------------------------------------

console.log(`\nSchedule: ${HOURS} hours from ${START_ISO}`);
console.log(`Hours delivered as designed: ${intact}   short: ${short}   over-delivered: ${over}`);

report("1. Predicted from the schedule as designed", merge(designedTallies));
report("2. Predicted from the schedule as delivered", merge(deliveredTallies));
report("3. Measured by the collector", live);
console.log(`\n   awaiting hindsight: ${pending[0][0]}`);

deliveredDisagreements.sort((a, b) => a.hour - b.hour);
console.log(`\nHours the delivered schedule made them disagree about: ${deliveredDisagreements.length}`);
for (const d of deliveredDisagreements) {
  console.log(
    `  h${String(d.hour).padStart(2)} ${d.service.padEnd(20)} observed=${String(d.observed).padStart(3)} ` +
      `surge=${String(d.surge).padEnd(5)} ` +
      DETECTORS.map((name, i) => `${name}=${d.boxes[i]}`).join(" "),
  );
}

if (damage.length) {
  console.log(`\nHours that did not arrive as written: ${damage.length}`);
  for (const d of damage) {
    console.log(`  h${String(d.hour).padStart(2)} ${d.state.padEnd(5)} ${d.notes.join(", ")}`);
  }
}
console.log();
