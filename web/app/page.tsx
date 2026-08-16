import Link from "next/link";
import {
  listGroups,
  listSparklines,
  listServices,
  countsByStatus,
  getOverviewTrend,
  getStorageStatus,
  type GroupSummary,
  type StorageStatus,
} from "@/lib/queries";
import { parseFilters, decodeCursor, toQueryString } from "@/lib/group-filters";
import type { GroupFilters } from "@/lib/group-filters";
import { relativeTime, bytesParts } from "@/lib/format";
import { Shell } from "@/app/components/shell/shell";
import { Panel } from "@/app/components/ui/panel";
import { StatTile } from "@/app/components/ui/stat-tile";
import { FilterBar } from "@/app/components/filter-bar";
import {
  LevelBadge,
  StatusBadge,
  DegradedBadge,
} from "@/app/components/ui/badge";
import { Sparkline, OverviewTrend } from "@/app/components/charts";

/** The plan suspends the project at this point rather than billing for it. */
const STORAGE_LIMIT = 512 * 1024 * 1024;

/** Enough to scan without scrolling forever; small enough that paging is real. */
const PAGE_SIZE = 25;

type LoadResult =
  | {
      ok: true;
      groups: GroupSummary[];
      nextCursor: string | null;
      sparklines: Map<number, number[]>;
      services: string[];
      counts: Record<string, number>;
      trend: { daily: number[]; total: number };
      storage: StorageStatus;
      ms: number;
    }
  | { ok: false; ms: number };

async function load(
  filters: GroupFilters,
  after: string | string[] | undefined,
): Promise<LoadResult> {
  const started = Date.now();
  try {
    // Two waves rather than one: the sparklines can only be asked for once the
    // page's group ids are known. Everything that does not depend on them goes
    // in the same round trip.
    const page = await listGroups(filters, decodeCursor(after), PAGE_SIZE);
    const ids = page.groups.map((group) => group.id);

    const [sparklines, services, counts, trend, storage] = await Promise.all([
      listSparklines(ids),
      listServices(),
      countsByStatus(filters),
      getOverviewTrend(filters),
      getStorageStatus(),
    ]);

    return {
      ok: true,
      groups: page.groups,
      nextCursor: page.nextCursor,
      sparklines,
      services,
      counts,
      trend,
      storage,
      ms: Date.now() - started,
    };
  } catch (error) {
    // The driver error can carry the host and role from the connection string,
    // so it stays in the server log and never reaches the page.
    console.error("group query failed", error);
    return { ok: false, ms: Date.now() - started };
  }
}

function GroupRow({
  group,
  series,
}: {
  group: GroupSummary;
  series: number[] | undefined;
}) {
  const regressed = group.status === "regressed";

  return (
    <li>
      <Link
        href={`/groups/${group.id}`}
        className={`flex items-center gap-3 rounded-xl border p-3.5 transition-colors sm:gap-4 sm:p-4 ${
          regressed
            ? "border-danger-edge bg-danger-bg hover:border-danger/50"
            : "border-edge bg-surface-1 hover:border-edge-strong hover:bg-surface-2"
        }`}
      >
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            {regressed ? (
              <StatusBadge status={group.status} />
            ) : (
              <LevelBadge level={group.level} />
            )}
            <h3 className="min-w-0 flex-1 truncate text-sm text-ink-hi">
              {group.title}
            </h3>
          </div>
          <p className="mt-1 truncate font-mono text-[11px] text-ink-low">
            {group.culprit ?? "no frame attributed"}
            {" · "}
            {group.service}
            {" · "}
            {group.platform}
          </p>
          {group.degraded_reason && (
            <p className="mt-1.5">
              <DegradedBadge reason={group.degraded_reason} />
            </p>
          )}
        </div>

        <div className="hidden sm:block">
          <Sparkline series={series} label={group.title} />
        </div>

        <div className="shrink-0 text-right">
          <span className="block text-base font-semibold tabular-nums text-ink-hi">
            {group.event_count}
          </span>
          <span className="block text-[11px] text-ink-low">
            {relativeTime(group.last_seen)}
          </span>
        </div>
      </Link>
    </li>
  );
}

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const filters = parseFilters(params);
  const result = await load(filters, params.after);
  const storage = result.ok ? bytesParts(result.storage.total_bytes) : null;

  const totalGroups = result.ok
    ? Object.values(result.counts).reduce((sum, n) => sum + n, 0)
    : 0;
  const filtered = Boolean(filters.service || filters.status || filters.q);

  return (
    <Shell current="groups" queryMs={result.ms}>
      <header className="mb-7">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          Overview
        </h1>
        <p className="mt-1 text-sm text-ink-low">
          Errors grouped by fingerprint, newest first.
        </p>
        {/*
          Nobody uses this deployment, so every fault below was written rather than
          reported. Muted on purpose: loud enough that no visitor mistakes this for
          production traffic, quiet enough that it is not the first thing the page
          is about.
        */}
        <p className="mt-3 flex items-start gap-2 text-xs leading-relaxed text-ink-faint">
          <span
            aria-hidden
            className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-warn"
          />
          <span>
            The faults below are a generated scenario, not real user traffic — written
            to give three anomaly detectors something to disagree about.{" "}
            <a
              className="text-accent-hi underline decoration-edge-strong underline-offset-2 hover:decoration-current"
              href="https://github.com/YusufKosarDev/stacklight#-the-traffic-behind-those-numbers-is-generated-not-real"
            >
              How it was built, and what it is for
            </a>
            .
          </span>
        </p>
      </header>

      {!result.ok && (
        <Panel className="border-danger-edge bg-danger-bg">
          <p className="text-sm text-danger">
            The group query failed. Details are in the server log.
          </p>
        </Panel>
      )}

      {result.ok && storage && (
        <>
          <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatTile label="Open faults" value={result.counts.open ?? 0} />
            <StatTile
              label="Events · 7d"
              value={result.trend.total}
              caption={filtered ? "matching this filter" : undefined}
            />
            <StatTile label="Regressed" value={result.counts.regressed ?? 0} />
            <StatTile
              label="Storage"
              value={storage.value}
              unit={storage.unit}
              meter={{
                fraction: result.storage.total_bytes / STORAGE_LIMIT,
                caption: `of 512 MB · ${
                  result.storage.last_sweep_at
                    ? `swept ${relativeTime(result.storage.last_sweep_at)}`
                    : "not swept yet"
                }`,
              }}
            />
          </div>

          <Panel className="mb-7">
            <OverviewTrend
              daily={result.trend.daily}
              total={result.trend.total}
            />
          </Panel>

          <h2 className="mb-3 text-sm font-medium text-ink">Groups</h2>

          <FilterBar
            filters={filters}
            services={result.services}
            counts={result.counts}
            total={totalGroups}
          />

          {result.groups.length === 0 ? (
            <Panel>
              <p className="text-sm text-ink-low">
                {filtered
                  ? "No groups match this filter."
                  : "No groups yet. Send an event to POST /api/events on the ingestion service."}
              </p>
            </Panel>
          ) : (
            <ul className="space-y-2">
              {result.groups.map((group) => (
                <GroupRow
                  key={group.id}
                  group={group}
                  series={result.sparklines.get(group.id)}
                />
              ))}
            </ul>
          )}

          {result.nextCursor && (
            /*
              Next only. Going back is the browser's back button, which on a
              list of GET URLs does exactly the right thing -- a "previous"
              link would mean either running the sort backwards or carrying a
              stack of cursors in the URL, for a control the browser already
              provides.
            */
            <div className="mt-4 flex justify-center">
              <Link
                href={`/${toQueryString(filters, result.nextCursor)}`}
                className="rounded-lg border border-edge bg-surface-1 px-4 py-2 text-sm text-ink transition-colors hover:border-edge-strong hover:bg-surface-2"
              >
                Older groups →
              </Link>
            </div>
          )}
        </>
      )}
    </Shell>
  );
}
