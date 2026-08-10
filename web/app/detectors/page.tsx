import { getDetectorScorecard, type DetectorRow } from "@/lib/queries";
import { Shell } from "@/app/components/shell/shell";
import { Panel } from "@/app/components/ui/panel";
import { StatTile } from "@/app/components/ui/stat-tile";

export const dynamic = "force-dynamic";

export const metadata = { title: "Detector scorecard — Stacklight" };

const DESCRIPTIONS: Record<string, string> = {
  poisson: "Upper-tail probability of this count under the rate recent hours imply.",
  ewma: "Exponentially weighted baseline; fires when the hour exceeds it by a multiple.",
  zscore: "Standard deviations above the trailing mean, with a floor under sigma.",
};

function ratio(numerator: number, denominator: number): string {
  if (denominator === 0) return "—";
  return `${Math.round((numerator / denominator) * 100)}%`;
}

function DetectorCard({ row }: { row: DetectorRow }) {
  const precisionDenominator = row.tp + row.fp;
  const recallDenominator = row.tp + row.fn;

  return (
    <li>
      <Panel>
        <div className="mb-1 flex flex-wrap items-center gap-2">
          <h3 className="font-mono text-sm font-medium text-ink-hi">
            {row.detector}
          </h3>
          {row.is_active ? (
            <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[11px] font-medium text-accent-hi ring-1 ring-inset ring-accent/25">
              active
            </span>
          ) : (
            <span className="rounded-full bg-surface-2 px-2 py-0.5 text-[11px] text-ink-low">
              shadow
            </span>
          )}
        </div>
        <p className="mb-4 text-sm text-ink-low">
          {DESCRIPTIONS[row.detector] ?? ""}
        </p>

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatTile
            label="Precision"
            value={ratio(row.tp, precisionDenominator)}
          />
          <StatTile label="Recall" value={ratio(row.tp, recallDenominator)} />
          <StatTile label="Fired" value={row.fired} />
          <StatTile label="Awaiting hindsight" value={row.pending} />
        </div>

        <p className="mt-3 font-mono text-[11px] text-ink-low">
          {row.tp} of {precisionDenominator} firings held up &middot; {row.tp} of{" "}
          {recallDenominator} surges caught &middot; {row.judged + row.pending}{" "}
          buckets judged
        </p>

        <div className="mt-2 flex flex-wrap gap-x-6 gap-y-1 font-mono text-[11px] text-ink-faint">
          <span>true positive {row.tp}</span>
          <span>false positive {row.fp}</span>
          <span>missed {row.fn}</span>
          <span>true negative {row.tn}</span>
        </div>
      </Panel>
    </li>
  );
}

/**
 * Query and its timing together, outside the component.
 *
 * Not a style preference: Date.now() in a component body is a call to an impure
 * function during render, and the lint rules reject it.
 */
async function load(): Promise<{
  rows: DetectorRow[];
  failed: boolean;
  ms: number;
}> {
  const started = Date.now();
  try {
    const rows = await getDetectorScorecard();
    return { rows, failed: false, ms: Date.now() - started };
  } catch (error) {
    console.error("scorecard query failed", error);
    return { rows: [], failed: true, ms: Date.now() - started };
  }
}

export default async function Page() {
  const { rows, failed, ms } = await load();
  const anyJudged = rows.some((row) => row.judged > 0);

  return (
    <Shell current="detectors" queryMs={ms}>
      <header className="mb-6">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          Detector scorecard
        </h1>
        <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-low">
          Three detectors judge every bucket. One is allowed to raise alerts; the
          others run in shadow and have their verdicts recorded anyway. Because
          they see identical data at the same moment, choosing between them is a
          table rather than an argument &mdash; and changing the active one is a
          configuration change whose effect was measured before it was made.
        </p>
      </header>

      {/*
        Tinted, because it is the page rather than a footnote to it. A scorecard
        that did not say this would be inviting the reader to believe something
        untrue about the numbers on it.
      */}
      <Panel className="mb-6 border-warn-edge bg-warn-bg">
        <h2 className="text-[10px] font-medium uppercase tracking-[0.09em] text-warn">
          What these numbers are not
        </h2>
        <div className="mt-2 max-w-xl space-y-2 text-sm leading-relaxed text-ink">
          <p>
            <strong className="text-ink-hi">This is not accuracy.</strong> Nobody
            labels these hours by hand. A verdict is scored against a rule
            applied in hindsight: an hour counts as a genuine surge when its
            count stands well clear of the rate around it, measured from both
            sides. A detector could score well by agreeing with a rule that is
            itself wrong.
          </p>
          <p>
            What the numbers <em>are</em> good for is comparing detectors against
            each other on the same data, which is the question being asked. They
            are shown unfiltered, including where a shadow beats the detector in
            charge.
          </p>
          <p>
            A bucket is only scored once enough hours have passed to judge it, so
            recent activity sits in &ldquo;awaiting hindsight&rdquo; rather than
            being counted early.
          </p>
        </div>
      </Panel>

      {failed && (
        <Panel className="border-danger-edge bg-danger-bg">
          <p className="text-sm text-danger">
            The scorecard query failed. Details are in the server log.
          </p>
        </Panel>
      )}

      {!failed && rows.length === 0 && (
        <Panel>
          <p className="text-sm text-ink-low">
            No detector has been asked to judge anything yet. A bucket is only
            evaluated once a group produces enough errors in an hour for any
            detector to be able to fire.
          </p>
        </Panel>
      )}

      {rows.length > 0 && (
        <>
          {!anyJudged && (
            <Panel className="mb-4">
              <p className="text-sm text-ink-low">
                Verdicts have been recorded but none are old enough to score yet.
                Precision and recall appear once the hindsight window has passed.
              </p>
            </Panel>
          )}
          <ul className="space-y-3">
            {rows.map((row) => (
              <DetectorCard key={row.detector} row={row} />
            ))}
          </ul>
        </>
      )}

      <section className="mt-8 max-w-xl">
        <h2 className="text-sm font-medium text-ink">
          Why this data breaks the textbook detectors
        </h2>
        <div className="mt-3 space-y-3 text-sm leading-relaxed text-ink-low">
          <p>
            EWMA and rolling z-score were built for a metric series: a continuous
            signal, sampled regularly, wobbling around a level. Error counts per
            group are not that. They are non-negative integers, usually small,
            arriving in bursts &mdash; and on this deployment,{" "}
            <strong className="text-ink-hi">
              97% of group-hour buckets are empty
            </strong>
            .
          </p>
          <p>
            Against a baseline of nearly nothing, &ldquo;several times the
            usual&rdquo; and &ldquo;many sigmas above the mean&rdquo; are both
            satisfied by the number two. Every detector here therefore sits
            behind an absolute floor on the count, which is less a tuning
            parameter than an admission that ratios carry little information down
            there.
          </p>
          <p>
            Above the floor they genuinely differ. A z-score divides by the
            spread, so a group that is bursty by nature desensitises the detector
            watching it. Poisson takes the spread from the rate instead, which
            suits count data &mdash; and pays for it when a group is genuinely
            erratic, where it fires more often than it should. Which failure
            costs more is not decidable from first principles. That is what the
            table above is for.
          </p>
        </div>
      </section>
    </Shell>
  );
}
