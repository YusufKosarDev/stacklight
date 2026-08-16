import type { Bucket } from "@/lib/queries";

/*
 * Chart tokens.
 *
 * The dashboard only ships a dark surface, so only the dark steps are defined.
 * Contrast against the page surface (#09090b) was measured for this palette
 * rather than inherited from the last one: SERIES is 4.58:1 and SERIES_HI is
 * 6.37:1, both clear of the 3:1 a graphical mark needs.
 *
 * Emphasis is carried by getting brighter rather than by everything else going
 * dim, which is why there is no muted step: the old palette had one and it sat
 * at 1.89:1, which is fine for a fill and not fine for the only thing marking a
 * bar as present.
 */
const SERIES = "#7c5cff";
const SERIES_HI = "#9b7cff";
const MUTED_INK = "#9a9aa6";
const GRIDLINE = "#1f1f28";

/**
 * Seven daily counts, drawn small enough to live in a table row.
 *
 * No axes, no labels: at this size they would out-weigh the data. The row already
 * carries the total and the last-seen time, so the sparkline only has to answer
 * "steady, spiking, or over?".
 *
 * The same window as the trend above the table, on purpose. A row drawn over a
 * different span from the chart it sits under is a page arguing with itself.
 */
export function Sparkline({
  series,
  label,
}: {
  series: number[] | undefined;
  label: string;
}) {
  const data = series ?? new Array(7).fill(0);
  const peak = Math.max(...data, 1);
  const total = data.reduce((sum, n) => sum + n, 0);

  return (
    <div
      className="flex h-8 items-end gap-[2px]"
      role="img"
      aria-label={`${label}: ${total} events over the last 7 days`}
    >
      {data.map((count, index) => {
        const height = count === 0 ? 2 : Math.max(3, (count / peak) * 32);
        const isCurrent = index === data.length - 1;
        return (
          <span
            key={index}
            style={{
              height: `${height}px`,
              width: "3px",
              borderRadius: count === 0 ? "1px" : "2px 2px 0 0",
              background: count === 0 ? GRIDLINE : isCurrent ? SERIES_HI : SERIES,
            }}
          />
        );
      })}
    </div>
  );
}

/**
 * The group's trend.
 *
 * A column chart: the job is comparing magnitude across time buckets, and the
 * buckets are discrete counts rather than a continuous signal, so columns say what
 * is meant more honestly than a line would. One series, so no legend — the heading
 * above already names what is plotted. Values are labelled selectively, at the peak
 * only; the rest live in the axis, the tooltips and the table below.
 */
export function TrendChart({
  buckets,
  range,
}: {
  buckets: Bucket[];
  range: string;
}) {
  const peak = Math.max(...buckets.map((b) => b.count), 1);
  const total = buckets.reduce((sum, b) => sum + b.count, 0);
  const peakIndex = buckets.findIndex((b) => b.count === peak && peak > 0);

  // Enough ticks to orient, few enough to stay readable at any width.
  const tickEvery = Math.max(1, Math.ceil(buckets.length / 6));

  return (
    <figure className="m-0">
      <figcaption className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm text-ink-low">
          Events per {range === "24h" ? "hour" : "day"}, from the rollups
        </span>
        <span className="font-mono text-xs" style={{ color: MUTED_INK }}>
          {total} in this window &middot; peak {peak}
        </span>
      </figcaption>

      <div className="overflow-x-auto">
        {/*
          Width scales with the number of buckets rather than being pinned at
          30rem. A fixed floor plus a per-bar cap left a seven-day chart sitting
          in the left third of its own panel with the rest empty, and forced a
          scrollbar onto ranges narrow enough not to need one.
        */}
        <div style={{ minWidth: `${Math.max(buckets.length * 18, 240)}px` }}>
          {/*
            Centred, because the bars are capped. Seven daily buckets across a
            desktop panel would otherwise be 150px slabs that read as a
            segmented bar rather than a series.
          */}
          <div
            className="relative flex h-40 items-end justify-center gap-[2px] border-b"
            style={{ borderColor: GRIDLINE }}
          >
            {/* One recessive gridline at the peak, so the tallest column has a reference. */}
            <span
              aria-hidden
              className="pointer-events-none absolute inset-x-0 top-0 border-t"
              style={{ borderColor: GRIDLINE }}
            />

            {buckets.map((bucket, index) => {
              const height =
                bucket.count === 0 ? 2 : Math.max(4, (bucket.count / peak) * 138);
              return (
                <div
                  key={bucket.iso}
                  className="group/bar relative flex flex-1 justify-center"
                  style={{ maxWidth: "48px" }}
                >
                  <span
                    className="w-full transition-opacity group-hover/bar:opacity-80"
                    style={{
                      height: `${height}px`,
                      borderRadius: bucket.count === 0 ? "1px" : "4px 4px 0 0",
                      background: bucket.count === 0 ? GRIDLINE : SERIES,
                    }}
                  />
                  {index === peakIndex && (
                    <span
                      className="pointer-events-none absolute -top-5 font-mono text-[10px] tabular-nums"
                      style={{ color: MUTED_INK }}
                    >
                      {bucket.count}
                    </span>
                  )}
                  {/* Per-mark tooltip. Pure hover, so the page stays a server component. */}
                  <span
                    className="pointer-events-none absolute bottom-full z-10 mb-1 hidden whitespace-nowrap rounded border border-edge bg-surface-0 px-2 py-1 font-mono text-[11px] text-ink shadow-lg group-hover/bar:block"
                    role="tooltip"
                  >
                    {bucket.label} &middot; {bucket.count}
                  </span>
                </div>
              );
            })}
          </div>

          <div className="mt-2 flex justify-center gap-[2px]">
            {buckets.map((bucket, index) => (
              <div
                key={bucket.iso}
                className="flex-1 text-center font-mono text-[10px] tabular-nums"
                style={{ maxWidth: "48px", color: MUTED_INK }}
              >
                {index % tickEvery === 0 ? bucket.label : " "}
              </div>
            ))}
          </div>
        </div>
      </div>

      <details className="mt-4">
        <summary className="cursor-pointer text-xs text-ink-low transition-colors hover:text-ink">
          Table view
        </summary>
        <div className="mt-2 max-h-56 overflow-auto rounded border border-edge">
          <table className="w-full text-left text-xs">
            {/* Opaque, not the translucent panel surface: this header is sticky
                and rows would scroll visibly through it. */}
            <thead className="sticky top-0 bg-surface-0">
              <tr className="text-ink-low">
                <th className="px-3 py-2 font-medium">Bucket (UTC)</th>
                <th className="px-3 py-2 text-right font-medium">Events</th>
              </tr>
            </thead>
            <tbody className="font-mono tabular-nums text-ink">
              {buckets.map((bucket) => (
                <tr key={bucket.iso} className="border-t border-edge">
                  <td className="px-3 py-1.5">{bucket.iso}</td>
                  <td className="px-3 py-1.5 text-right">{bucket.count}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </figure>
  );
}

/**
 * Every group's events, by day, for the last week.
 *
 * Takes an already-summed array rather than querying, so the aggregate is one
 * value the page is handed rather than a second trip to the database on the
 * critical read path.
 *
 * A week rather than a day because the rollups outlive the events behind them,
 * and a front page that could only reach back 24 hours went blank on a
 * deployment whose traffic arrives in bursts -- showing nothing while the
 * history it was drawn from was intact.
 *
 * @param daily 7 counts, oldest first
 */
export function OverviewTrend({
  daily,
  total,
}: {
  daily: number[];
  total: number;
}) {
  const peak = Math.max(...daily, 1);
  const peakIndex = daily.findIndex((count) => count === peak && peak > 0);

  return (
    <figure className="m-0">
      <figcaption className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium text-ink">
          All events, last 7 days
        </span>
        <span className="font-mono text-xs tabular-nums text-ink-low">
          {total} total &middot; peak {peak}
        </span>
      </figcaption>

      <div
        className="flex h-24 items-end gap-[3px] border-b"
        style={{ borderColor: GRIDLINE }}
        role="img"
        aria-label={`${total} events over the last 7 days, peaking at ${peak} in one day`}
      >
        {daily.map((count, index) => (
          <span
            key={index}
            className="flex-1 rounded-t-[3px]"
            style={{
              height:
                count === 0 ? "2px" : `${Math.max(4, (count / peak) * 92)}px`,
              background:
                count === 0 ? GRIDLINE : index === peakIndex ? SERIES_HI : SERIES,
            }}
          />
        ))}
      </div>

      <div className="mt-2 flex justify-between font-mono text-[10px] tabular-nums text-ink-faint">
        <span>7d ago</span>
        <span>3d</span>
        <span>today</span>
      </div>
    </figure>
  );
}
