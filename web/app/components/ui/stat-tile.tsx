/**
 * One headline number.
 *
 * The meter is optional and exists for storage, where the number only means
 * something against the plan limit that would suspend the project.
 */
export function StatTile({
  label,
  value,
  unit,
  meter,
}: {
  label: string;
  value: string | number;
  unit?: string;
  meter?: { fraction: number; caption: string };
}) {
  return (
    <div className="relative overflow-hidden rounded-xl border border-edge bg-surface-1 p-4">
      {/* A hairline of accent along the top edge, fading out. Enough to make the
          tiles read as a set without adding another border colour. */}
      <span
        aria-hidden
        className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-accent/60 to-transparent"
      />
      <span className="block text-[10px] font-medium uppercase tracking-[0.09em] text-ink-low">
        {label}
      </span>
      <span className="mt-1 block truncate text-2xl font-semibold tracking-tight tabular-nums text-ink-hi">
        {value}
        {unit && <span className="ml-1 text-sm text-ink-low">{unit}</span>}
      </span>
      {meter && (
        <>
          <div className="mt-3 h-1 overflow-hidden rounded-full bg-edge">
            <div
              className="h-full rounded-full bg-gradient-to-r from-accent-lo to-accent-hi"
              style={{
                width: `${Math.max(1, Math.min(100, meter.fraction * 100))}%`,
              }}
            />
          </div>
          <span className="mt-1.5 block text-[10px] text-ink-low">
            {meter.caption}
          </span>
        </>
      )}
    </div>
  );
}
