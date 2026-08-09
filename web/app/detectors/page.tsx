import Link from "next/link";
import { getDetectorScorecard, type DetectorRow } from "@/lib/queries";

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

function Metric({
  label,
  value,
  hint,
}: {
  label: string;
  value: string;
  hint: string;
}) {
  return (
    <div>
      <div className="text-zinc-500">{label}</div>
      <div className="font-mono text-lg text-zinc-100">{value}</div>
      <div className="text-[11px] text-zinc-600">{hint}</div>
    </div>
  );
}

function DetectorCard({ row }: { row: DetectorRow }) {
  const precisionDenominator = row.tp + row.fp;
  const recallDenominator = row.tp + row.fn;

  return (
    <li className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-5">
      <div className="mb-1 flex flex-wrap items-center gap-2">
        <h3 className="font-mono text-sm font-medium text-zinc-100">
          {row.detector}
        </h3>
        {row.is_active ? (
          <span className="rounded bg-sky-500/10 px-2 py-0.5 font-mono text-[11px] text-sky-300 ring-1 ring-inset ring-sky-500/30">
            active
          </span>
        ) : (
          <span className="rounded bg-zinc-500/10 px-2 py-0.5 font-mono text-[11px] text-zinc-400 ring-1 ring-inset ring-zinc-600/30">
            shadow
          </span>
        )}
      </div>
      <p className="mb-4 text-sm text-zinc-400">
        {DESCRIPTIONS[row.detector] ?? ""}
      </p>

      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Metric
          label="Precision"
          value={ratio(row.tp, precisionDenominator)}
          hint={`${row.tp} of ${precisionDenominator} firings held up`}
        />
        <Metric
          label="Recall"
          value={ratio(row.tp, recallDenominator)}
          hint={`${row.tp} of ${recallDenominator} surges caught`}
        />
        <Metric
          label="Fired"
          value={String(row.fired)}
          hint={`of ${row.judged + row.pending} buckets judged`}
        />
        <Metric
          label="Awaiting hindsight"
          value={String(row.pending)}
          hint="too recent to score"
        />
      </dl>

      <div className="mt-4 flex flex-wrap gap-x-6 gap-y-1 font-mono text-xs text-zinc-500">
        <span>true positive {row.tp}</span>
        <span>false positive {row.fp}</span>
        <span>missed {row.fn}</span>
        <span>true negative {row.tn}</span>
      </div>
    </li>
  );
}

export default async function Page() {
  let rows: DetectorRow[] = [];
  let failed = false;
  try {
    rows = await getDetectorScorecard();
  } catch (error) {
    console.error("scorecard query failed", error);
    failed = true;
  }

  const anyJudged = rows.some((row) => row.judged > 0);

  return (
    <main className="mx-auto w-full max-w-3xl flex-1 px-6 py-12">
      <Link
        href="/"
        className="text-sm text-zinc-400 transition-colors hover:text-zinc-200"
      >
        &larr; All groups
      </Link>

      <h1 className="mt-4 text-2xl font-semibold tracking-tight text-zinc-100">
        Detector scorecard
      </h1>
      <p className="mt-2 text-sm leading-relaxed text-zinc-400">
        Three detectors judge every bucket. One is allowed to raise alerts; the
        others run in shadow and have their verdicts recorded anyway. Because
        they see identical data at the same moment, choosing between them is a
        table rather than an argument &mdash; and changing the active one is a
        configuration change whose effect was measured before it was made.
      </p>

      <section className="mt-6 rounded-lg border border-amber-900/40 bg-amber-950/15 p-4">
        <h2 className="text-xs font-medium uppercase tracking-wider text-amber-300/80">
          What these numbers are not
        </h2>
        <div className="mt-2 space-y-2 text-sm leading-relaxed text-amber-100/70">
          <p>
            <strong className="text-amber-100">This is not accuracy.</strong>{" "}
            Nobody labels these hours by hand. A verdict is scored against a rule
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
      </section>

      {failed && (
        <p className="mt-6 rounded-lg border border-red-900/50 bg-red-950/30 px-4 py-3 text-sm text-red-300">
          The scorecard query failed. Details are in the server log.
        </p>
      )}

      {!failed && rows.length === 0 && (
        <p className="mt-6 rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 text-sm text-zinc-400">
          No detector has been asked to judge anything yet. A bucket is only
          evaluated once a group produces enough errors in an hour for any
          detector to be able to fire.
        </p>
      )}

      {rows.length > 0 && (
        <>
          {!anyJudged && (
            <p className="mt-6 rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 text-sm text-zinc-400">
              Verdicts have been recorded but none are old enough to score yet.
              Precision and recall appear once the hindsight window has passed.
            </p>
          )}
          <ul className="mt-6 space-y-4">
            {rows.map((row) => (
              <DetectorCard key={row.detector} row={row} />
            ))}
          </ul>
        </>
      )}

      <section className="mt-8 rounded-lg border border-zinc-800 bg-zinc-900/40 p-5">
        <h2 className="text-sm font-medium text-zinc-100">
          Why this data breaks the textbook detectors
        </h2>
        <div className="mt-3 space-y-3 text-sm leading-relaxed text-zinc-400">
          <p>
            EWMA and rolling z-score were built for a metric series: a
            continuous signal, sampled regularly, wobbling around a level. Error
            counts per group are not that. They are non-negative integers,
            usually small, arriving in bursts &mdash; and on this deployment,{" "}
            <strong className="text-zinc-200">
              97% of group-hour buckets are empty
            </strong>
            .
          </p>
          <p>
            Against a baseline of nearly nothing, &ldquo;several times the
            usual&rdquo; and &ldquo;many sigmas above the mean&rdquo; are both
            satisfied by the number two. Every detector here therefore sits
            behind an absolute floor on the count, which is less a tuning
            parameter than an admission that ratios carry little information
            down there.
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
    </main>
  );
}
