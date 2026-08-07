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

export function relativeTime(utcTimestamp: string): string {
  const then = new Date(utcTimestamp.replace(" ", "T") + "Z").getTime();
  const seconds = Math.round((Date.now() - then) / 1000);

  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.round(seconds / 3600)}h ago`;
  return `${Math.round(seconds / 86400)}d ago`;
}
