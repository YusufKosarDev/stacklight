import type { Bucket } from "@/lib/queries";

/*
 * Chart tokens.
 *
 * The dashboard only ships a dark surface, so only the dark steps are defined.
 * `--series` is the validated dark-mode blue; it clears 3:1 against the page
 * surface (#09090b) and sits inside the dark lightness band.
 */
const SERIES = "#3987e5";
const SERIES_MUTED = "#1c3f6b";
const MUTED_INK = "#898781";
const GRIDLINE = "#2c2c2a";

/**
 * Twenty-four hourly counts, drawn small enough to live in a table row.
 *
 * No axes, no labels: at this size they would out-weigh the data. The row already
 * carries the total and the last-seen time, so the sparkline only has to answer
 * "steady, spiking, or over?".
 */
export function Sparkline({
  series,
  label,
}: {
  series: number[] | undefined;
  label: string;
}) {
  const data = series ?? new Array(24).fill(0);
  const peak = Math.max(...data, 1);
  const total = data.reduce((sum, n) => sum + n, 0);

  return (
    <div
      className="flex h-8 items-end gap-[2px]"
      role="img"
      aria-label={`${label}: ${total} events over the last 24 hours`}
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
              background: count === 0 ? GRIDLINE : isCurrent ? SERIES : SERIES_MUTED,
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
        <span className="text-sm text-zinc-400">
          Events per {range === "24h" ? "hour" : "day"}, from the rollups
        </span>
        <span className="font-mono text-xs" style={{ color: MUTED_INK }}>
          {total} in this window &middot; peak {peak}
        </span>
      </figcaption>

      <div className="overflow-x-auto">
        <div className="min-w-[30rem]">
          <div
            className="relative flex h-40 items-end gap-[2px] border-b"
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
                bucket.count === 0 ? 2 : Math.max(4, (bucket.count / peak) * 156);
              return (
                <div
                  key={bucket.iso}
                  className="group/bar relative flex flex-1 justify-center"
                  style={{ maxWidth: "24px" }}
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
                    className="pointer-events-none absolute bottom-full z-10 mb-1 hidden whitespace-nowrap rounded border border-zinc-700 bg-zinc-950 px-2 py-1 font-mono text-[11px] text-zinc-200 shadow-lg group-hover/bar:block"
                    role="tooltip"
                  >
                    {bucket.label} &middot; {bucket.count}
                  </span>
                </div>
              );
            })}
          </div>

          <div className="mt-2 flex gap-[2px]">
            {buckets.map((bucket, index) => (
              <div
                key={bucket.iso}
                className="flex-1 text-center font-mono text-[10px] tabular-nums"
                style={{ maxWidth: "24px", color: MUTED_INK }}
              >
                {index % tickEvery === 0 ? bucket.label : " "}
              </div>
            ))}
          </div>
        </div>
      </div>

      <details className="mt-4">
        <summary className="cursor-pointer text-xs text-zinc-500 transition-colors hover:text-zinc-300">
          Table view
        </summary>
        <div className="mt-2 max-h-56 overflow-auto rounded border border-zinc-800">
          <table className="w-full text-left text-xs">
            <thead className="sticky top-0 bg-zinc-900">
              <tr className="text-zinc-500">
                <th className="px-3 py-2 font-medium">Bucket (UTC)</th>
                <th className="px-3 py-2 text-right font-medium">Events</th>
              </tr>
            </thead>
            <tbody className="font-mono tabular-nums text-zinc-300">
              {buckets.map((bucket) => (
                <tr key={bucket.iso} className="border-t border-zinc-800/60">
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
