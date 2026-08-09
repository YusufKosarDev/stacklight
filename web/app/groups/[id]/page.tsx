import Link from "next/link";
import { notFound } from "next/navigation";
import {
  getGroup,
  findSimilarGroups,
  getGroupSeries,
  type Range,
} from "@/lib/queries";
import {
  levelStyle,
  statusStyle,
  relativeTime,
  DEGRADED_REASONS,
} from "@/lib/format";
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

  const group = await getGroup(groupId);
  if (!group) {
    notFound();
  }

  const [similar, series] = await Promise.all([
    findSimilarGroups(group.id, group.title),
    getGroupSeries(group.id, range),
  ]);

  const frames = group.frames ?? [];
  const inAppCount = frames.filter((frame) => frame.inApp).length;
  const status = statusStyle(group.status);

  return (
    <main className="mx-auto w-full max-w-5xl flex-1 px-6 py-12">
      <Link
        href="/"
        className="text-sm text-zinc-400 transition-colors hover:text-zinc-200"
      >
        &larr; All groups
      </Link>

      <header className="mt-4 mb-8">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`inline-flex rounded px-2 py-0.5 font-mono text-xs ring-1 ring-inset ${levelStyle(
              group.level,
            )}`}
          >
            {group.level}
          </span>
          <span
            className={`inline-flex items-center gap-1 rounded px-2 py-0.5 font-mono text-xs ring-1 ring-inset ${status.className}`}
          >
            <span aria-hidden>{status.icon}</span>
            {status.label}
          </span>
          <span className="font-mono text-xs text-zinc-400">{group.service}</span>
          <span className="text-xs text-zinc-500">{group.platform}</span>
        </div>
        <h1 className="mt-2 text-xl font-semibold tracking-tight text-zinc-100">
          {group.title}
        </h1>
        <p className="mt-1 font-mono text-sm text-zinc-500">
          {group.culprit ?? "no frame attributed"}
        </p>

        <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-sm">
          <div>
            <dt className="text-zinc-500">Events</dt>
            <dd className="font-mono text-zinc-200">{group.event_count}</dd>
          </div>
          <div>
            <dt className="text-zinc-500">First seen</dt>
            <dd className="font-mono text-zinc-200">
              {group.first_seen} ({relativeTime(group.first_seen)})
            </dd>
          </div>
          <div>
            <dt className="text-zinc-500">Last seen</dt>
            <dd className="font-mono text-zinc-200">
              {group.last_seen} ({relativeTime(group.last_seen)})
            </dd>
          </div>
          {(group.release_first || group.release_last) && (
            <div>
              <dt className="text-zinc-500">Releases</dt>
              <dd className="font-mono text-zinc-200">
                {group.release_first}
                {group.release_last !== group.release_first
                  ? ` → ${group.release_last}`
                  : ""}
              </dd>
            </div>
          )}
        </dl>
      </header>

      {group.status === "regressed" && (
        <p className="mb-8 rounded-lg border border-red-900/50 bg-red-950/25 px-4 py-3 text-sm text-red-200/90">
          <span className="font-mono text-xs uppercase tracking-wider text-red-300/70">
            regression
          </span>
          <br />
          This group was resolved
          {group.resolved_in_release ? ` in ${group.resolved_in_release}` : ""} and
          came back
          {group.regressed_in_release ? ` in ${group.regressed_in_release}` : ""}
          {group.regressed_at ? `, ${relativeTime(group.regressed_at)}` : ""}. The
          fix did not hold.
        </p>
      )}

      {group.sampled_count > 0 && (
        <p className="mb-8 rounded-lg border border-amber-900/50 bg-amber-950/20 px-4 py-3 text-sm text-amber-200/90">
          <span className="font-mono text-xs uppercase tracking-wider text-amber-300/70">
            sampled
          </span>
          <br />
          {group.sampled_count} of these events were counted in the trend but their
          detail was not stored: the group went over its hourly cap. The chart
          below is complete; the stack traces behind part of it are not.
        </p>
      )}

      {group.degraded_reason && (
        <p className="mb-8 rounded-lg border border-amber-900/50 bg-amber-950/20 px-4 py-3 text-sm text-amber-200/90">
          <span className="font-mono text-xs uppercase tracking-wider text-amber-300/70">
            {group.degraded_reason}
          </span>
          <br />
          {DEGRADED_REASONS[group.degraded_reason] ??
            "Grouping used weaker signal than in-app frames."}
        </p>
      )}

      <section className="mb-8 rounded-lg border border-zinc-800 bg-zinc-900/40 p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-sm font-medium uppercase tracking-wider text-zinc-400">
            Frequency
          </h2>
          <div className="flex gap-1">
            {RANGES.map((option) => (
              <Link
                key={option.key}
                href={`/groups/${group.id}?range=${option.key}`}
                className={`rounded px-2 py-1 font-mono text-xs transition-colors ${
                  option.key === range
                    ? "bg-zinc-700 text-zinc-100"
                    : "text-zinc-500 hover:text-zinc-300"
                }`}
              >
                {option.label}
              </Link>
            ))}
          </div>
        </div>
        <TrendChart buckets={series} range={range} />
      </section>

      <section className="mb-8">
        <h2 className="mb-1 text-sm font-medium uppercase tracking-wider text-zinc-400">
          How this group was decided
        </h2>
        <p className="mb-4 text-sm text-zinc-500">
          Every step below is a pure function of the event. The same event always
          produces the same group.
        </p>

        <ol className="space-y-4">
          <li className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-4">
            <h3 className="mb-2 text-xs font-medium uppercase tracking-wider text-zinc-500">
              1 &middot; Parse the stack trace
            </h3>
            {frames.length === 0 ? (
              <p className="text-sm text-zinc-400">
                No frames were parsed from this event.
              </p>
            ) : (
              <>
                <p className="mb-3 text-sm text-zinc-400">
                  {frames.length} frames parsed, {inAppCount} belong to the
                  application. Only those decide the group &mdash; the same bug
                  reaches the runtime through different framework paths depending
                  on the request.
                </p>
                <div className="overflow-x-auto">
                  <ul className="min-w-[34rem] space-y-1 font-mono text-xs">
                    {frames.map((frame, index) => (
                      <li
                        key={index}
                        className={`flex items-baseline gap-3 rounded px-2 py-1 ${
                          frame.inApp
                            ? "bg-emerald-500/5 text-zinc-200"
                            : "text-zinc-600"
                        }`}
                      >
                        <span
                          className={`w-14 shrink-0 text-[10px] uppercase tracking-wider ${
                            frame.inApp ? "text-emerald-400/80" : "text-zinc-600"
                          }`}
                        >
                          {frame.inApp ? "in-app" : "vendor"}
                        </span>
                        <span className="truncate">{frameLabel(frame)}</span>
                        <span className="ml-auto shrink-0 text-zinc-600">
                          {frame.file}
                          {frame.line > 0 ? `:${frame.line}` : ""}
                        </span>
                      </li>
                    ))}
                  </ul>
                </div>
              </>
            )}
          </li>

          <li className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-4">
            <h3 className="mb-2 text-xs font-medium uppercase tracking-wider text-zinc-500">
              2 &middot; Build the fingerprint input
            </h3>
            <p className="mb-3 text-sm text-zinc-400">
              Line numbers are dropped so that editing a line above the throw
              site does not open a new group. Values inside the message are
              replaced with placeholders.
            </p>
            <pre className="overflow-x-auto rounded bg-zinc-950/70 p-3 font-mono text-xs text-zinc-300">
              {group.fingerprint_input}
            </pre>
          </li>

          <li className="rounded-lg border border-zinc-800 bg-zinc-900/40 p-4">
            <h3 className="mb-2 text-xs font-medium uppercase tracking-wider text-zinc-500">
              3 &middot; Hash it
            </h3>
            <p className="mb-3 text-sm text-zinc-400">
              SHA-256 of the text above, truncated to 128 bits. The version is
              part of the group key: a future algorithm opens new groups rather
              than rewriting these.
            </p>
            <div className="flex flex-wrap gap-x-8 gap-y-2 text-sm">
              <div>
                <div className="text-zinc-500">Fingerprint</div>
                <div className="font-mono text-zinc-200">
                  {group.fingerprint}
                </div>
              </div>
              <div>
                <div className="text-zinc-500">Algorithm version</div>
                <div className="font-mono text-zinc-200">
                  v{group.fingerprint_version}
                </div>
              </div>
            </div>
          </li>
        </ol>
      </section>

      {group.sample_stacktrace && (
        <section className="mb-8">
          <h2 className="mb-1 text-sm font-medium uppercase tracking-wider text-zinc-400">
            Latest stored event
          </h2>
          <p className="mb-3 text-sm text-zinc-500">
            {group.sample_received_at} UTC &middot; {group.sample_message}
          </p>
          <pre className="overflow-x-auto rounded-lg border border-zinc-800 bg-zinc-950/70 p-4 font-mono text-xs text-zinc-400">
            {group.sample_stacktrace}
          </pre>
        </section>
      )}

      <section>
        <h2 className="mb-1 text-sm font-medium uppercase tracking-wider text-zinc-400">
          Similar groups
        </h2>
        <p className="mb-3 text-sm text-zinc-500">
          Trigram similarity computed by Postgres against a GIN index. It catches
          what a fingerprint cannot &mdash; the same fault one refactor away from
          an existing group. No model is involved.
        </p>
        {similar.length === 0 ? (
          <p className="rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 text-sm text-zinc-500">
            Nothing above the 0.3 similarity threshold.
          </p>
        ) : (
          <ul className="space-y-2">
            {similar.map((candidate) => (
              <li key={candidate.id}>
                <Link
                  href={`/groups/${candidate.id}`}
                  className="flex items-center justify-between gap-4 rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 transition-colors hover:border-zinc-600"
                >
                  <span className="truncate text-sm text-zinc-200">
                    {candidate.title}
                  </span>
                  <span className="shrink-0 font-mono text-xs text-zinc-500">
                    {candidate.score} &middot; {candidate.event_count} events
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
