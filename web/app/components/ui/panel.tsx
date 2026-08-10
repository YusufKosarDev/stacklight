import type { ReactNode } from "react";

/**
 * The bordered surface every card on the dashboard is made of.
 *
 * One component rather than the same four utility classes repeated on every
 * page, which is what the dashboard had and why nothing ever moved together.
 */
export function Panel({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-xl border border-edge bg-surface-1 p-4 sm:p-5 ${className}`}
    >
      {children}
    </section>
  );
}

/** Title on the left, a secondary figure on the right. */
export function PanelHeader({
  title,
  aside,
}: {
  title: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="text-sm font-medium text-ink">{title}</h2>
      {aside && (
        <span className="font-mono text-xs tabular-nums text-ink-low">
          {aside}
        </span>
      )}
    </div>
  );
}
