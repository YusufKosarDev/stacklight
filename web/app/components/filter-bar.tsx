import Link from "next/link";
import type { GroupFilters, OverviewRange } from "@/lib/group-filters";
import {
  toQueryString,
  DEFAULT_RANGE,
  NO_FILTERS,
} from "@/lib/group-filters";

/**
 * The group list's controls.
 *
 * A plain `<form method="get">` and a row of links. Both work with JavaScript
 * disabled and, more to the point here, without shipping any: submitting a GET
 * form is something the browser has always done on its own, and the result is a
 * URL the reader can bookmark and share.
 */

const STATUS_CHIPS: { key: GroupFilters["status"]; label: string }[] = [
  { key: null, label: "All" },
  { key: "open", label: "Open" },
  { key: "regressed", label: "Regressed" },
  { key: "resolved", label: "Resolved" },
  { key: "ignored", label: "Ignored" },
];

export function FilterBar({
  filters,
  range,
  services,
  counts,
  total,
}: {
  filters: GroupFilters;
  range: OverviewRange;
  services: string[];
  counts: Record<string, number>;
  total: number;
}) {
  const chipCount = (key: GroupFilters["status"]) =>
    key === null ? total : (counts[key] ?? 0);

  return (
    <div className="mb-4 space-y-3">
      <form method="get" className="flex flex-wrap items-center gap-2">
        {/* The status chips are links, so the form must carry the current one
            forward or submitting a search would silently clear it. The range is
            a row of links for the same reason and needs the same treatment:
            without this, searching from a 30-day view would drop the reader back
            to seven days without saying so. */}
        {filters.status && (
          <input type="hidden" name="status" value={filters.status} />
        )}
        {range !== DEFAULT_RANGE && (
          <input type="hidden" name="range" value={range} />
        )}

        <input
          type="search"
          name="q"
          defaultValue={filters.q ?? ""}
          placeholder="Search titles"
          aria-label="Search group titles"
          className="min-w-0 flex-1 rounded-lg border border-edge bg-surface-1 px-3 py-1.5 text-sm text-ink placeholder:text-ink-faint focus:border-accent/50 focus:outline-none sm:flex-none sm:basis-64"
        />

        <select
          name="service"
          defaultValue={filters.service ?? ""}
          aria-label="Filter by service"
          className="rounded-lg border border-edge bg-surface-1 px-3 py-1.5 text-sm text-ink focus:border-accent/50 focus:outline-none"
        >
          <option value="">All services</option>
          {services.map((service) => (
            <option key={service} value={service}>
              {service}
            </option>
          ))}
        </select>

        <button
          type="submit"
          className="rounded-lg border border-edge bg-surface-2 px-3 py-1.5 text-sm text-ink transition-colors hover:border-edge-strong hover:text-ink-hi"
        >
          Apply
        </button>

        {(filters.service || filters.q || filters.status) && (
          <Link
            href={`/${toQueryString(NO_FILTERS, { range })}`}
            className="px-2 py-1.5 text-sm text-ink-low transition-colors hover:text-ink"
          >
            Clear
          </Link>
        )}
      </form>

      <div className="flex flex-wrap gap-1.5">
        {STATUS_CHIPS.map((chip) => {
          const active = filters.status === chip.key;
          return (
            <Link
              key={chip.label}
              href={`/${toQueryString({ ...filters, status: chip.key }, { range })}`}
              aria-current={active ? "true" : undefined}
              className={`rounded-full px-2.5 py-1 text-xs transition-colors ${
                active
                  ? "bg-accent/15 text-ink-hi ring-1 ring-inset ring-accent/25"
                  : "bg-surface-1 text-ink-low hover:bg-surface-2 hover:text-ink"
              }`}
            >
              {chip.label}
              <span className="ml-1.5 tabular-nums text-ink-faint">
                {chipCount(chip.key)}
              </span>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
