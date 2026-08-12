/**
 * What the designed schedule should produce, worked out before it was sent.
 *
 * The point is to know what the scorecard should say in advance, so that a run producing
 * no disagreement can be told apart from a scenario that was never capable of producing
 * any. The arithmetic lives in detectors.mjs and is shared with the check made afterwards
 * against the hours actually delivered; one copy, so the two sides cannot drift.
 *
 *   node tools/traffic/simulate.mjs
 */
import { SERVICES, HOURS, totalEvents } from "./scenario.mjs";
import { DETECTORS, merge, report, tallyService } from "./detectors.mjs";

const tallies = [];
const disagreements = [];

for (const service of SERVICES) {
  const counts = Array.from({ length: HOURS }, (_, h) => service.count(h));
  const result = tallyService(counts, { horizon: HOURS });
  tallies.push(result.tally);
  for (const d of result.disagreements) disagreements.push({ service: service.name, ...d });
}

console.log(`\nSchedule as designed: ${HOURS} hours, ${SERVICES.length} services, ${totalEvents()} events`);
report("Predicted", merge(tallies));

disagreements.sort((a, b) => a.hour - b.hour);
console.log(`\nHours where the detectors disagree: ${disagreements.length}`);
for (const d of disagreements) {
  console.log(
    `  h${String(d.hour).padStart(2)} ${d.service.padEnd(20)} observed=${String(d.observed).padStart(3)} ` +
      `surge=${String(d.surge).padEnd(5)} ` +
      DETECTORS.map((name, i) => `${name}=${d.boxes[i]}`).join(" "),
  );
}
console.log();
