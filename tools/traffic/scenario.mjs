/**
 * A generated scenario, not real traffic.
 *
 * Nobody uses this deployment, so the detector scorecard sat on three buckets with all
 * three detectors at 100% and nothing to choose between them. This file exists to give
 * them something to disagree about, and it is written as data so that the disagreement
 * can be predicted before it is measured rather than discovered afterwards.
 *
 * ## What makes a shape worth including
 *
 * The three detectors differ in exactly two places, and every profile below aims at one
 * of them:
 *
 *   - `ewma` weighs recent hours more heavily; `zscore` and `poisson` both use the flat
 *     mean. A group whose recent hours do not look like its average therefore splits the
 *     first from the other two -- that is the ramp and the one that goes quiet.
 *   - `zscore` divides by the spread it observes, with a floor under it; `poisson` takes
 *     the spread from the rate itself. A group that is steadier than a rate implies, or
 *     burstier than one implies, splits those two -- that is the flat profile and the
 *     bursty one.
 *
 * Each profile is named for the failure it is meant to provoke, and the detector it
 * embarrasses is named with it. Two of the seven aim at `poisson`, which is the detector
 * currently in charge: a scenario that only exposed the alternatives would be worth
 * nothing as evidence for keeping it.
 *
 * ## Hours, not events
 *
 * Rollup buckets are written as `date_trunc('hour', now())`, so an event lands in the
 * hour it arrives and history cannot be back-filled. The counts below are therefore a
 * schedule against the wall clock: hour 0 is the first hour after START, and the driver
 * runs once an hour to send that hour's counts. No detector may fire before six hours of
 * history exist, and a verdict is not scored until three hours after its bucket, so the
 * first hours here buy nothing except the history the later ones are judged against.
 */

/** Hour 0 of the schedule. Everything else is derived from it, so the run is repeatable. */
export const START_ISO = "2026-08-11T15:00:00Z";

/** The schedule stops itself. Past this the driver sends nothing and exits clean. */
export const HOURS = 30;

const ramp = (from, to, hour, span) =>
  Math.round(from + ((to - from) * hour) / (span - 1));

/**
 * One fault per service, which is what an incident actually looks like: the same thing
 * failing repeatedly rather than a spread of unrelated ones. Different services carry
 * different platforms and exception types so both stack parsers are exercised, and the
 * messages carry values the normalizer is supposed to strip -- if it stops doing that,
 * these groups fragment and the counts stop matching this file.
 */
export const SERVICES = [
  {
    name: "checkout-api",
    platform: "java",
    exceptionType: "java.lang.IllegalStateException",
    aims: "zscore false positive",
    why:
      "Flat and steady, so the observed spread collapses onto the sigma floor and a rise " +
      "of half again reads as six sigma. Poisson sees the same hour as unremarkable for " +
      "a rate of ten, which it is.",
    // Ordinary hours near ten, with three modest rises that are not surges.
    count: (h) => [9, 10, 11, 10, 9, 10, 11, 10, 9, 16, 10, 9, 11, 10, 10,
                   9, 10, 16, 11, 10, 9, 10, 11, 10, 9, 16, 10, 11, 9, 10][h],
  },
  {
    name: "search-indexer",
    platform: "javascript",
    exceptionType: "TypeError",
    aims: "zscore false negative",
    why:
      "Bursty by nature: idle for three hours, then forty. The spread that behaviour " +
      "creates is the same spread the z-score divides by, so the group desensitises the " +
      "detector watching it and a genuine spike lands inside its own noise.",
    count: (h) => {
      if (h === 27) return 45; // the real one, after a quiet stretch
      if (h >= 24) return 0;
      return h % 4 === 3 ? [40, 38, 42, 39, 41, 40][(h - 3) / 4] : 0;
    },
  },
  {
    name: "media-transcoder",
    platform: "javascript",
    exceptionType: "RangeError",
    aims: "poisson false positive",
    why:
      "Genuinely erratic: quiet for hours, then a peak of twenty-five. That peak is this " +
      "group's ordinary Tuesday, but it is far into the upper tail of a Poisson rate " +
      "fitted to its mean, so the detector calls a routine peak a surprise.",
    count: (h) => [10, 11, 10, 10, 11, 40][h % 6],
  },
  {
    name: "notification-worker",
    platform: "java",
    exceptionType: "java.lang.NullPointerException",
    aims: "poisson false positive",
    why:
      "A slow ramp that then levels off, which is the shape of a leak somebody caps " +
      "rather than fixes. Nothing here is a departure from the local rate, but the flat " +
      "mean lags a rising trend and Poisson measures against the lagging figure. It " +
      "levels off deliberately: a ramp that climbed for thirty hours would hand this " +
      "detector a fresh false positive every hour and let one profile decide the whole " +
      "comparison.",
    count: (h) => (h < 20 ? ramp(4, 40, h, 20) : 40),
  },
  {
    name: "payments-api",
    platform: "java",
    exceptionType: "org.springframework.dao.DataAccessResourceFailureException",
    aims: "the control: all three should agree",
    why:
      "A calm baseline and two unmistakable spikes. If a detector misses these it is not " +
      "measuring anything, so this profile is the one that says the comparison is wired " +
      "up at all.",
    count: (h) => (h === 14 || h === 26 ? 40 : [5, 6, 7, 6, 5, 6, 7, 6][h % 8]),
  },
  {
    name: "session-store",
    platform: "javascript",
    exceptionType: "Error",
    aims: "ewma false positive, and the silence detector",
    why:
      "Busy for half a day, then nothing, then a small return. Twelve quiet hours decay " +
      "the weighted baseline to the floor, so twelve events read as twenty-four times " +
      "normal while the flat mean still remembers the busy half and shrugs.",
    count: (h) => {
      if (h < 12) return [30, 28, 32, 30, 29, 31, 30, 28, 30, 32, 29, 30][h];
      if (h === 24) return 12; // the small return
      return 0;
    },
  },
];

/** Which hour of the schedule `now` falls in, or null when the run has not begun or is over. */
export function hourIndex(now = new Date(), startIso = START_ISO) {
  const elapsed = now.getTime() - new Date(startIso).getTime();
  if (elapsed < 0) return null;
  const hour = Math.floor(elapsed / 3_600_000);
  return hour < HOURS ? hour : null;
}

/** What to send for a given hour: every service that has anything to say. */
export function plan(hour) {
  if (hour === null || hour < 0 || hour >= HOURS) return [];
  return SERVICES.map((service) => ({ service, count: service.count(hour) })).filter(
    (entry) => entry.count > 0,
  );
}

/**
 * What an hour still owes, given what the collector already holds for it.
 *
 * The scheduler running this is best-effort and skips: of the first eighteen hours it
 * fired for eleven, and the hours it dropped happened to be the ones carrying the cases
 * the detectors disagree about. Running more often is the obvious answer and the wrong
 * one on its own, because two ticks in the same hour would send that hour twice -- which
 * already happened once, and doubling a count turns a routine peak into a real surge and
 * destroys the very case it was there to make.
 *
 * So a tick sends the difference rather than the plan. Reading what is already there
 * makes the send idempotent within the hour: a tick that finds the hour complete sends
 * nothing, and a tick that finds it half-delivered finishes it. That is what makes a
 * twenty-minute cadence safe, and it is also what keeps the cadence cheap -- most ticks
 * end here, without a single request to a collector that would otherwise wake up for it.
 */
export function remaining(work, actual) {
  return work
    .map(({ service, count }) => {
      const have = actual.get(service.name) ?? 0;
      return { service, target: count, have, count: Math.max(0, count - have) };
    })
    .filter((entry) => entry.count > 0);
}

/** Every hour of the schedule for one service, which is what the tests reason about. */
export function series(serviceName) {
  const service = SERVICES.find((s) => s.name === serviceName);
  if (!service) throw new Error(`no such service: ${serviceName}`);
  return Array.from({ length: HOURS }, (_, h) => service.count(h));
}

/** Total events the whole schedule will send, which is the storage question. */
export function totalEvents() {
  return SERVICES.reduce(
    (sum, service) =>
      sum + Array.from({ length: HOURS }, (_, h) => service.count(h)).reduce((a, b) => a + b, 0),
    0,
  );
}
