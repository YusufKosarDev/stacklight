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
