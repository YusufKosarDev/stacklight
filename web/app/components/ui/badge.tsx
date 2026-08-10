import type { ReactNode } from "react";
import type { GroupStatus } from "@/lib/queries";

/**
 * Every chip on the dashboard.
 *
 * These were three separate lookup maps returning raw class strings --
 * LEVEL_STYLES and STATUS_STYLES in lib/format.ts and KIND_STYLES inside the
 * alerts page -- each leaving the call site to assemble the element by hand.
 * That is why the same chip markup appeared on four pages and drifted between
 * them.
 */
function Chip({
  className,
  icon,
  children,
}: {
  className: string;
  icon?: string;
  children: ReactNode;
}) {
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${className}`}
    >
      {icon && <span aria-hidden>{icon}</span>}
      {children}
    </span>
  );
}

const LEVELS: Record<string, string> = {
  ERROR: "bg-danger-bg text-danger",
  WARN: "bg-warn-bg text-warn",
  INFO: "bg-accent/15 text-accent-hi",
};

export function LevelBadge({ level }: { level: string }) {
  const key = level.toUpperCase();
  return (
    <Chip className={LEVELS[key] ?? "bg-surface-2 text-ink-low"}>
      {level.toLowerCase()}
    </Chip>
  );
}

/**
 * `regressed` is deliberately the loudest of the four: a group that came back
 * after being called fixed is a worse signal than one nobody has looked at yet.
 * The icon carries the distinction too, so it never rests on colour alone.
 */
const STATUSES: Record<
  string,
  { label: string; icon: string; className: string }
> = {
  open: { label: "open", icon: "●", className: "bg-surface-2 text-ink-low" },
  resolved: { label: "resolved", icon: "✓", className: "bg-ok/15 text-ok" },
  ignored: {
    label: "ignored",
    icon: "◌",
    className: "bg-surface-2 text-ink-faint",
  },
  regressed: {
    label: "regressed",
    icon: "↺",
    className: "bg-danger-bg text-danger",
  },
};

export function StatusBadge({ status }: { status: GroupStatus | string }) {
  const style = STATUSES[status] ?? STATUSES.open;
  return (
    <Chip className={style.className} icon={style.icon}>
      {style.label}
    </Chip>
  );
}

const KINDS: Record<
  string,
  { label: string; icon: string; className: string }
> = {
  spike: { label: "spike", icon: "▲", className: "bg-warn-bg text-warn" },
  new_group: {
    label: "new error",
    icon: "✦",
    className: "bg-accent/15 text-accent-hi",
  },
  regression: {
    label: "regression",
    icon: "↺",
    className: "bg-danger-bg text-danger",
  },
  // The only kind raised by nothing arriving rather than something.
  silence: {
    label: "went quiet",
    icon: "◌",
    className: "bg-accent/15 text-accent-hi",
  },
};

export function AlertKindBadge({ kind }: { kind: string }) {
  const style = KINDS[kind] ?? {
    label: kind,
    icon: "•",
    className: "bg-surface-2 text-ink-low",
  };
  return (
    <Chip className={style.className} icon={style.icon}>
      {style.label}
    </Chip>
  );
}

/** Says grouping had to fall back to weaker signal than in-app frames. */
export function DegradedBadge({ reason }: { reason: string }) {
  return <Chip className="bg-warn-bg font-mono text-warn">{reason}</Chip>;
}
