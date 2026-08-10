import Link from "next/link";
import { notFound } from "next/navigation";
import {
  getGroup,
  findSimilarGroups,
  getGroupSeries,
  type Range,
} from "@/lib/queries";
import { relativeTime, DEGRADED_REASONS } from "@/lib/format";
import { Shell } from "@/app/components/shell/shell";
import { Panel, PanelHeader } from "@/app/components/ui/panel";
import { StatTile } from "@/app/components/ui/stat-tile";
import { LevelBadge, StatusBadge } from "@/app/components/ui/badge";
import { TrendChart } from "@/app/components/charts";

export const dynamic = "force-dynamic";

const RANGES: { key: Range; label: string }[] = [
  { key: "24h", label: "24 hours" },
  { key: "7d", label: "7 days" },
  { key: "30d", label: "30 days" },
];

function frameLabel(frame: {
  declaringClass: string | null;
  function: string | null;
  file: string | null;
  line: number;
}) {
  const scope = frame.declaringClass ?? frame.file ?? "<unknown>";
  const fn = frame.function ?? "<anonymous>";
  return `${scope}.${fn}`;
}

/** A tinted panel for the things a group can be in trouble about. */
function Notice({
  tag,
  tone,
  children,
}: {
  tag: string;
  tone: "danger" | "warn";
  children: React.ReactNode;
}) {
  const styles =
    tone === "danger"
      ? "border-danger-edge bg-danger-bg"
      : "border-warn-edge bg-warn-bg";
  const tagColor = tone === "danger" ? "text-danger" : "text-warn";

  return (
    <div className={`mb-5 rounded-xl border p-4 sm:p-5 ${styles}`}>
      <span
        className={`block font-mono text-[10px] uppercase tracking-[0.09em] ${tagColor}`}
      >
        {tag}
      </span>
      <p className="mt-1.5 text-sm leading-relaxed text-ink">{children}</p>
    </div>
  );
}

/**
 * Queries and their timing together, outside the component.
 *
 * Not a style preference: Date.now() in a component body is a call to an impure
 * function during render, and the lint rules reject it.
 *
 * @returns null when no group has that id, so the caller can call notFound()
 */
async function load(groupId: number, range: Range) {
  const started = Date.now();

  const group = await getGroup(groupId);
  if (!group) {
    return null;
  }

  const [similar, series] = await Promise.all([
    findSimilarGroups(group.id, group.title),
    getGroupSeries(group.id, range),
  ]);

  return { group, similar, series, ms: Date.now() - started };
}

export default async function GroupPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ range?: string }>;
}) {
  const { id } = await params;
  const { range: rawRange } = await searchParams;
  const groupId = Number(id);

  if (!Number.isInteger(groupId) || groupId <= 0) {
    notFound();
  }

  const range: Range = RANGES.some((r) => r.key === rawRange)
    ? (rawRange as Range)
    : "24h";

  const loaded = await load(groupId, range);
  if (!loaded) {
    notFound();
  }
  const { group, similar, series, ms } = loaded;

  const frames = group.frames ?? [];
  const inAppCount = frames.filter((frame) => frame.inApp).length;

  return (
    <Shell current="groups" queryMs={ms}>
      <header className="mb-6">
        <div className="flex flex-wrap items-center gap-2">
          <LevelBadge level={group.level} />
          <StatusBadge status={group.status} />
          <span className="font-mono text-xs text-ink-low">
            {group.service}
          </span>
          <span className="text-xs text-ink-faint">{group.platform}</span>
        </div>
        <h1 className="mt-2.5 text-lg font-semibold leading-snug tracking-tight text-ink-hi sm:text-xl">
          {group.title}
        </h1>
        <p className="mt-1 break-all font-mono text-xs text-ink-low">
          {group.culprit ?? "no frame attributed"}
        </p>
      </header>

      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label="Events" value={group.event_count} />
        <StatTile
          label="First seen"
          value={relativeTime(group.first_seen)}
          caption={`${group.first_seen} UTC`}
        />
        <StatTile
          label="Last seen"
          value={relativeTime(group.last_seen)}
          caption={`${group.last_seen} UTC`}
        />
        <StatTile
          label="Releases"
          value={
            group.release_first
              ? group.release_last !== group.release_first
                ? `${group.release_first} → ${group.release_last}`
                : group.release_first
              : "—"
          }
        />
      </div>

      {group.status === "regressed" && (
        <Notice tag="regression" tone="danger">
          This group was resolved
          {group.resolved_in_release
            ? ` in ${group.resolved_in_release}`
            : ""}{" "}
          and came back
          {group.regressed_in_release ? ` in ${group.regressed_in_release}` : ""}
          {group.regressed_at ? `, ${relativeTime(group.regressed_at)}` : ""}.
          The fix did not hold.
        </Notice>
      )}

      {group.sampled_count > 0 && (
        <Notice tag="sampled" tone="warn">
          {group.sampled_count} of these events were counted in the trend but
          their detail was not stored: the group went over its hourly cap. The
          chart below is complete; the stack traces behind part of it are not.
        </Notice>
      )}

      {group.degraded_reason && (
        <Notice tag={group.degraded_reason} tone="warn">
          {DEGRADED_REASONS[group.degraded_reason] ??
            "Grouping used weaker signal than in-app frames."}
        </Notice>
      )}

      <Panel className="mb-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-sm font-medium text-ink">Frequency</h2>
          <div className="flex gap-1">
            {RANGES.map((option) => (
              <Link
                key={option.key}
                href={`/groups/${group.id}?range=${option.key}`}
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
        </div>
        <TrendChart buckets={series} range={range} />
      </Panel>

      <section className="mb-5">
        <h2 className="mb-1 text-sm font-medium text-ink">
          How this group was decided
        </h2>
        <p className="mb-4 text-sm text-ink-low">
          Every step below is a pure function of the event. The same event always
          produces the same group.
        </p>

        <ol className="space-y-3">
          <li>
            <Panel>
              <PanelHeader title="1 · Parse the stack trace" />
              {frames.length === 0 ? (
                <p className="text-sm text-ink-low">
                  No frames were parsed from this event.
                </p>
              ) : (
                <>
                  <p className="mb-3 text-sm text-ink-low">
                    {frames.length} frames parsed, {inAppCount} belong to the
                    application. Only those decide the group &mdash; the same bug
                    reaches the runtime through different framework paths
                    depending on the request.
                  </p>
                  <div className="overflow-x-auto">
                    <ul className="min-w-[34rem] space-y-1 font-mono text-xs">
                      {frames.map((frame, index) => (
                        <li
                          key={index}
                          className={`flex items-baseline gap-3 rounded px-2 py-1 ${
                            frame.inApp
                              ? "bg-accent/8 text-ink"
                              : "text-ink-faint"
                          }`}
                        >
                          <span
                            className={`w-14 shrink-0 text-[10px] uppercase tracking-[0.09em] ${
                              frame.inApp ? "text-accent-hi" : "text-ink-faint"
                            }`}
                          >
                            {frame.inApp ? "in-app" : "vendor"}
                          </span>
                          <span className="truncate">{frameLabel(frame)}</span>
                          <span className="ml-auto shrink-0 text-ink-faint">
                            {frame.file}
                            {frame.line > 0 ? `:${frame.line}` : ""}
                          </span>
                        </li>
                      ))}
                    </ul>
                  </div>
                </>
              )}
            </Panel>
          </li>

          <li>
            <Panel>
              <PanelHeader title="2 · Build the fingerprint input" />
              <p className="mb-3 text-sm text-ink-low">
                Line numbers are dropped so that editing a line above the throw
                site does not open a new group. Values inside the message are
                replaced with placeholders.
              </p>
              <pre className="overflow-x-auto rounded-lg border border-edge bg-surface-0 p-3 font-mono text-[11px] leading-relaxed text-ink">
                {group.fingerprint_input}
              </pre>
            </Panel>
          </li>

          <li>
            <Panel>
              <PanelHeader title="3 · Hash it" />
              <p className="mb-3 text-sm text-ink-low">
                SHA-256 of the text above, truncated to 128 bits. The version is
                part of the group key: a future algorithm opens new groups rather
                than rewriting these.
              </p>
              <div className="flex flex-wrap gap-x-8 gap-y-3 text-sm">
                <div className="min-w-0">
                  <div className="text-[10px] font-medium uppercase tracking-[0.09em] text-ink-low">
                    Fingerprint
                  </div>
                  <div className="break-all font-mono text-ink-hi">
                    {group.fingerprint}
                  </div>
                </div>
                <div>
                  <div className="text-[10px] font-medium uppercase tracking-[0.09em] text-ink-low">
                    Algorithm version
                  </div>
                  <div className="font-mono text-ink-hi">
                    v{group.fingerprint_version}
                  </div>
                </div>
              </div>
            </Panel>
          </li>
        </ol>
      </section>

      {group.sample_stacktrace && (
        <Panel className="mb-5">
          <PanelHeader
            title="Latest stored event"
            aside={`${group.sample_received_at} UTC`}
          />
          <p className="mb-3 text-sm text-ink-low">{group.sample_message}</p>
          <pre className="overflow-x-auto rounded-lg border border-edge bg-surface-0 p-3 font-mono text-[11px] leading-relaxed text-ink-low">
            {group.sample_stacktrace}
          </pre>
        </Panel>
      )}

      <section>
        <h2 className="mb-1 text-sm font-medium text-ink">Similar groups</h2>
        <p className="mb-3 text-sm text-ink-low">
          Trigram similarity computed by Postgres against a GIN index. It catches
          what a fingerprint cannot &mdash; the same fault one refactor away from
          an existing group. No model is involved.
        </p>
        {similar.length === 0 ? (
          <Panel>
            <p className="text-sm text-ink-low">
              Nothing above the 0.3 similarity threshold.
            </p>
          </Panel>
        ) : (
          <ul className="space-y-2">
            {similar.map((candidate) => (
              <li key={candidate.id}>
                <Link
                  href={`/groups/${candidate.id}`}
                  className="flex items-center justify-between gap-4 rounded-xl border border-edge bg-surface-1 px-4 py-3 transition-colors hover:border-edge-strong hover:bg-surface-2"
                >
                  <span className="truncate text-sm text-ink">
                    {candidate.title}
                  </span>
                  <span className="shrink-0 font-mono text-xs tabular-nums text-ink-low">
                    {candidate.score} &middot; {candidate.event_count} events
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </Shell>
  );
}
