import Link from "next/link";
import {
  listGroups,
  listSparklines,
  getStorageStatus,
  type GroupSummary,
  type StorageStatus,
} from "@/lib/queries";
import { summarise, type OverviewSummary } from "@/lib/overview";
import { relativeTime, bytesParts } from "@/lib/format";
import { Shell } from "@/app/components/shell/shell";
import { Panel } from "@/app/components/ui/panel";
import { StatTile } from "@/app/components/ui/stat-tile";
import {
  LevelBadge,
  StatusBadge,
  DegradedBadge,
} from "@/app/components/ui/badge";
import { Sparkline, OverviewTrend } from "@/app/components/charts";

/** The plan suspends the project at this point rather than billing for it. */
const STORAGE_LIMIT = 512 * 1024 * 1024;

type LoadResult =
  | {
      ok: true;
      groups: GroupSummary[];
      sparklines: Map<number, number[]>;
      storage: StorageStatus;
      summary: OverviewSummary;
      ms: number;
    }
  | { ok: false; ms: number };

async function load(): Promise<LoadResult> {
  const started = Date.now();
  try {
    const [groups, sparklines, storage] = await Promise.all([
      listGroups(),
      listSparklines(),
      getStorageStatus(),
    ]);
    return {
      ok: true,
      groups,
      sparklines,
      storage,
      summary: summarise(groups, sparklines),
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

export default async function Page() {
  const result = await load();
  const storage = result.ok ? bytesParts(result.storage.total_bytes) : null;

  return (
    <Shell current="groups" queryMs={result.ms}>
      <header className="mb-7">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          Overview
        </h1>
        <p className="mt-1 text-sm text-ink-low">
          Errors grouped by fingerprint, newest first.
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
            <StatTile label="Open faults" value={result.summary.openCount} />
            <StatTile label="Events · 24h" value={result.summary.events24h} />
            <StatTile label="Regressed" value={result.summary.regressedCount} />
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
              hourly={result.summary.hourly}
              total={result.summary.events24h}
            />
          </Panel>

          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-sm font-medium text-ink">Groups</h2>
            <span className="font-mono text-xs tabular-nums text-ink-low">
              {result.summary.openCount} open · {result.summary.regressedCount}{" "}
              regressed · {result.summary.resolvedCount} resolved
            </span>
          </div>

          {result.groups.length === 0 ? (
            <Panel>
              <p className="text-sm text-ink-low">
                No groups yet. Send an event to{" "}
                <code className="font-mono text-ink">POST /api/events</code> on
                the ingestion service.
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
        </>
      )}
    </Shell>
  );
}
