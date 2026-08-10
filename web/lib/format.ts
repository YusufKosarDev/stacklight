export const LEVEL_STYLES: Record<string, string> = {
  ERROR: "bg-red-500/10 text-red-300 ring-red-500/30",
  WARN: "bg-amber-500/10 text-amber-300 ring-amber-500/30",
  INFO: "bg-sky-500/10 text-sky-300 ring-sky-500/30",
};

export function levelStyle(level: string) {
  return (
    LEVEL_STYLES[level.toUpperCase()] ??
    "bg-zinc-500/10 text-zinc-300 ring-zinc-500/30"
  );
}

/** Why grouping had to use weaker signal than in-app frames. */
export const DEGRADED_REASONS: Record<string, string> = {
  no_frames:
    "No stack trace was sent, so the normalized message is the only thing identifying this group.",
  no_in_app_frames:
    "Every frame belonged to a framework or the runtime, so vendor frames had to be used. Grouping is coarser than usual.",
  minified:
    "The frames look minified. Minified names change on every build, so grouping them would open a new group per deploy; the normalized message was used instead.",
};

/**
 * Group state.
 *
 * `regressed` is deliberately the loudest: a group that came back after being called
 * fixed is a worse signal than one nobody has looked at yet, and the icon carries the
 * distinction so it never rests on colour alone.
 */
export const STATUS_STYLES: Record<
  string,
  { label: string; icon: string; className: string }
> = {
  open: {
    label: "open",
    icon: "●",
    className: "bg-zinc-500/10 text-zinc-300 ring-zinc-500/30",
  },
  resolved: {
    label: "resolved",
    icon: "✓",
    className: "bg-emerald-500/10 text-emerald-300 ring-emerald-500/30",
  },
  ignored: {
    label: "ignored",
    icon: "◌",
    className: "bg-zinc-500/5 text-zinc-500 ring-zinc-600/30",
  },
  regressed: {
    label: "regressed",
    icon: "↺",
    className: "bg-red-500/15 text-red-300 ring-red-500/40",
  },
};

export function statusStyle(status: string) {
  return STATUS_STYLES[status] ?? STATUS_STYLES.open;
}

/**
 * Splits the figure from its unit so a caller can set them at different sizes.
 *
 * The alternative was slicing the formatted string apart at the call site,
 * which works right up until a unit gains a character.
 */
export function bytesParts(bytes: number): { value: string; unit: string } {
  if (bytes < 1024) return { value: String(bytes), unit: "B" };
  if (bytes < 1024 * 1024)
    return { value: (bytes / 1024).toFixed(0), unit: "KB" };
  return { value: (bytes / 1024 / 1024).toFixed(1), unit: "MB" };
}

export function formatBytes(bytes: number): string {
  const { value, unit } = bytesParts(bytes);
  return `${value} ${unit}`;
}

export function relativeTime(utcTimestamp: string): string {
  const then = new Date(utcTimestamp.replace(" ", "T") + "Z").getTime();
  const seconds = Math.round((Date.now() - then) / 1000);

  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ago`;
  return `${Math.round(seconds / 86400)}d ago`;
}
