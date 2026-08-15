import Link from "next/link";
import type { NavCounts } from "@/lib/queries";

export type NavSection = "groups" | "alerts" | "detectors" | "grouping";

const LINKS: { key: NavSection; href: string; label: string }[] = [
  { key: "groups", href: "/", label: "Groups" },
  { key: "alerts", href: "/alerts", label: "Alerts" },
  { key: "detectors", href: "/detectors", label: "Detectors" },
  { key: "grouping", href: "/how-grouping-works", label: "How grouping works" },
];

function countFor(key: NavSection, counts: NavCounts | null): number | null {
  if (!counts) return null;
  if (key === "groups") return counts.open_groups;
  if (key === "alerts") return counts.recent_alerts;
  return null;
}

export function Sidebar({
  current,
  counts,
  queryMs,
}: {
  current: NavSection;
  counts: NavCounts | null;
  queryMs?: number;
}) {
  return (
    <div className="flex h-full flex-col gap-4 lg:gap-0">
      <Link href="/" className="flex items-center gap-2.5">
        <span
          aria-hidden
          className="h-4 w-4 shrink-0 rounded-[5px] bg-gradient-to-br from-accent-hi to-accent-lo shadow-[0_0_14px_rgba(124,92,255,0.5)]"
        />
        <span className="text-[13px] font-semibold tracking-tight text-ink-hi">
          Stacklight
        </span>
      </Link>

      {/*
        Wraps rather than scrolls on a narrow screen. Four short links fit on two
        rows, and a scrolling nav both hides the last item and leaves a native
        scrollbar sitting across a dark surface.
      */}
      <nav className="flex flex-1 flex-wrap gap-1 lg:mt-5 lg:flex-col lg:flex-nowrap">
        {LINKS.map((link) => {
          const active = link.key === current;
          const count = countFor(link.key, counts);
          return (
            <Link
              key={link.key}
              href={link.href}
              aria-current={active ? "page" : undefined}
              className={`flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-[13px] transition-colors ${
                active
                  ? "bg-accent/15 text-ink-hi ring-1 ring-inset ring-accent/25"
                  : "text-ink-low hover:bg-surface-2 hover:text-ink"
              }`}
            >
              {link.label}
              {count !== null && (
                <span className="rounded-full bg-surface-2 px-1.5 text-[10px] tabular-nums text-ink-low lg:ml-auto">
                  {count}
                </span>
              )}
            </Link>
          );
        })}
      </nav>

      {/*
        The architectural claim, on every page. The dashboard talks to Postgres
        and nothing else, which is what lets it render in full while the
        ingestion service is asleep.
      */}
      <div className="hidden border-t border-edge pt-4 lg:block">
        <span className="block text-[10px] font-medium uppercase tracking-[0.09em] text-ink-faint">
          Read path
        </span>
        <span className="mt-1.5 flex items-center gap-2 text-[11px] text-ink">
          <span
            aria-hidden
            className="h-1.5 w-1.5 shrink-0 rounded-full bg-ok shadow-[0_0_7px_rgba(61,214,140,0.8)]"
          />
          <span>
            Postgres
            {queryMs !== undefined && (
              <span className="tabular-nums">
                {" · "}
                {(queryMs / 1000).toFixed(2)} s
              </span>
            )}
          </span>
        </span>
        <span className="mt-1.5 block text-[10px] leading-relaxed text-ink-faint">
          Renders whether or not the ingestion service is awake.
        </span>
      </div>

      {/*
        Said on every page rather than once on the front one. Everything here is a
        written scenario, and a dashboard full of convincing-looking faults should
        say so wherever somebody happens to land on it.
      */}
      <div className="hidden border-t border-edge pt-4 lg:block">
        <span className="block text-[10px] font-medium uppercase tracking-[0.09em] text-ink-faint">
          Data
        </span>
        <span className="mt-1.5 flex items-center gap-2 text-[11px] text-ink">
          <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full bg-warn" />
          <span>Generated scenario</span>
        </span>
        <span className="mt-1.5 block text-[10px] leading-relaxed text-ink-faint">
          Not real user traffic.{" "}
          <a
            className="text-accent-hi underline decoration-edge-strong underline-offset-2 hover:decoration-current"
            href="https://github.com/YusufKosarDev/stacklight#-the-traffic-behind-those-numbers-is-generated-not-real"
          >
            Why, and what it is for
          </a>
          .
        </span>
      </div>
    </div>
  );
}
