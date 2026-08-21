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
import {
  parseFilters,
  parseRange,
  decodeCursor,
  toQueryString,
  OVERVIEW_RANGES,
  DEFAULT_RANGE,
  NO_FILTERS,
} from "@/lib/group-filters";
import type { GroupFilters, OverviewRange } from "@/lib/group-filters";
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

/** The last window there is to fall back on, so widening cannot loop. */
const WIDEST_RANGE = OVERVIEW_RANGES[OVERVIEW_RANGES.length - 1].key;

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
      /** The window the trend above actually covers, after any widening. */
      range: OverviewRange;
      /** Whether that window is wider than the one the page started with. */
      widened: boolean;
      ms: number;
    }
  | { ok: false; ms: number };

/**
 * @param asked the window the URL named, or null to let the data choose one
 */
async function load(
  filters: GroupFilters,
  asked: OverviewRange | null,
  after: string | string[] | undefined,
): Promise<LoadResult> {
  const started = Date.now();
  const range = asked ?? DEFAULT_RANGE;
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
      getOverviewTrend(filters, range),
      getStorageStatus(),
    ]);

    /*
     * Nobody named a window, the default one is empty, and there are events
     * somewhere: widen rather than draw a row of zeroes.
     *
     * Only when nobody named one. Widening under a reader who has just clicked
     * "7 days" would give them a button that appears not to work.
     *
     * The cost is a third round trip, and it lands where it does no harm. A
     * deployment with traffic in the last week never takes this branch; one that
     * has been quiet for a week takes it on every load and pays the ten or so
     * milliseconds Neon needs, which nobody is waiting on. The `event_rows`
     * check keeps a fork's first run -- an empty database -- from paying even
     * that for a query whose answer is already known.
     */
    const widened =
      asked === null &&
      trend.total === 0 &&
      storage.event_rows > 0 &&
      range !== WIDEST_RANGE;

    return {
      ok: true,
      groups: page.groups,
      nextCursor: page.nextCursor,
      sparklines,
      services,
      counts,
      trend: widened ? await getOverviewTrend(filters, WIDEST_RANGE) : trend,
      storage,
      range: widened ? WIDEST_RANGE : range,
      widened,
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

/**
 * What the trend panel says when the window it was asked for holds nothing.
 *
 * A chart of seven zero-height bars is not an answer, it is the absence of one:
 * a reader cannot tell a quiet week from a broken query, an empty database or a
 * dashboard pointed at the wrong place. Every one of those is worth
 * distinguishing, and only the page knows which it is.
 *
 * The deployment this runs on makes the distinction matter more than it usually
 * would. Its faults come from a generated scenario that ran once and stopped, so
 * the default window goes empty a week later and stays that way -- and the
 * honest thing to say is that this is a recorded window rather than a live feed,
 * with a link to one that still has the data in it.
 */
function QuietWindow({
  range,
  filters,
  filtered,
  newestEvent,
}: {
  range: OverviewRange;
  filters: GroupFilters;
  filtered: boolean;
  newestEvent: string | null;
}) {
  // Widening the window cannot help when a filter is what emptied it, and
  // offering that link anyway would send the reader somewhere equally blank.
  // Widening cannot help when a filter is what emptied the window, and there is
  // nothing wider to offer once this is already the widest. The page reaches this
  // component having tried that widening itself unless the reader named a window,
  // so in practice the link appears for exactly one case: somebody asked for seven
  // days, and seven days are empty.
  const wider = !filtered && range !== WIDEST_RANGE ? WIDEST_RANGE : null;

  return (
    <div className="flex h-24 flex-col justify-center gap-1.5">
      <p className="text-sm text-ink">
        {filtered
          ? `No events in the last ${range} match this filter.`
          : `No events in the last ${range}.`}
      </p>

      {!filtered && newestEvent && (
        <p className="text-xs leading-relaxed text-ink-low">
          The last one arrived {relativeTime(newestEvent)}, on{" "}
          <span className="font-mono text-ink">{newestEvent} UTC</span>. The
          faults below are a scenario that ran once and stopped, so this is a
          recorded window rather than a live feed.
        </p>
      )}

      {wider && (
        <p>
          <Link
            href={`/${toQueryString(filters, { range: wider })}`}
            className="text-xs text-accent-hi underline decoration-edge-strong underline-offset-2 transition-colors hover:decoration-current"
          >
            Show {wider} →
          </Link>
        </p>
      )}

      {filtered && (
        <p>
          <Link
            href={`/${toQueryString(NO_FILTERS, { range })}`}
            className="text-xs text-accent-hi underline decoration-edge-strong underline-offset-2 transition-colors hover:decoration-current"
          >
            Clear the filter →
          </Link>
        </p>
      )}
    </div>
  );
}

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = await searchParams;
  const filters = parseFilters(params);
  const asked = parseRange(params);
  const result = await load(filters, asked, params.after);
  // What the page ended up showing, which is what every link on it must carry.
  const range = result.ok ? result.range : (asked ?? DEFAULT_RANGE);
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
              label={`Events · ${range}`}
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
            <div className="mb-4 flex flex-wrap items-center justify-end gap-1">
              {OVERVIEW_RANGES.map((option) => (
                <Link
                  key={option.key}
                  href={`/${toQueryString(filters, { range: option.key })}`}
                  aria-current={option.key === range ? "true" : undefined}
                  className={`rounded-lg px-2.5 py-1 text-xs transition-colors ${
                    option.key === range
                      ? "bg-accent/15 text-ink-hi ring-1 ring-inset ring-accent/25"
                      : "text-ink-low hover:bg-surface-2 hover:text-ink"
                  }`}
                >
                  {option.label}
                </Link>
              ))}
            </div>

            {result.trend.total === 0 && result.storage.event_rows > 0 ? (
              <QuietWindow
                range={range}
                filters={filters}
                filtered={filtered}
                newestEvent={result.storage.newest_event}
              />
            ) : (
              <>
                {result.widened && (
                  /*
                    The chart says "last 30 days" in its own caption, and the
                    switcher marks which window is in force. This line is for the
                    thing neither of them can say: that the reader did not ask for
                    this one, and why they got it.
                  */
                  <p className="mb-3 text-xs text-ink-low">
                    No events in the last {DEFAULT_RANGE} — showing {range}.
                  </p>
                )}
                <OverviewTrend
                  daily={result.trend.daily}
                  total={result.trend.total}
                  range={range}
                />
              </>
            )}
          </Panel>

          <h2 className="mb-3 text-sm font-medium text-ink">Groups</h2>

          <FilterBar
            filters={filters}
            range={range}
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
                href={`/${toQueryString(filters, { range, after: result.nextCursor })}`}
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
