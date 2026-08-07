import Link from "next/link";
import { listGroups, type GroupSummary } from "@/lib/queries";
import { levelStyle, relativeTime } from "@/lib/format";

// Rendered per request. Without this the build would try to prerender the page
// and reach for the database at build time.
export const dynamic = "force-dynamic";

type LoadResult =
  | { ok: true; groups: GroupSummary[]; ms: number }
  | { ok: false; ms: number };

async function load(): Promise<LoadResult> {
  const started = Date.now();
  try {
    const groups = await listGroups();
    return { ok: true, groups, ms: Date.now() - started };
  } catch (error) {
    // The driver error can carry the host and role from the connection string,
    // so it stays in the server log and never reaches the page.
    console.error("group query failed", error);
    return { ok: false, ms: Date.now() - started };
  }
}

export default async function Page() {
  const result = await load();

  return (
    <main className="mx-auto w-full max-w-5xl flex-1 px-6 py-12">
      <header className="mb-8 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-zinc-100">
            Stacklight
          </h1>
          <p className="mt-1 text-sm text-zinc-400">
            Errors grouped by fingerprint, newest first.
          </p>
        </div>
        <Link
          href="/how-grouping-works"
          className="rounded-md border border-zinc-700 px-3 py-1.5 text-sm text-zinc-300 transition-colors hover:border-zinc-500 hover:text-zinc-100"
        >
          How grouping works
        </Link>
      </header>

      <section className="mb-8 rounded-lg border border-zinc-800 bg-zinc-900/40 p-4">
        <h2 className="text-xs font-medium uppercase tracking-wider text-zinc-500">
          Read path
        </h2>
        <p className="mt-2 text-sm text-zinc-300">
          This page is a server component querying Postgres directly over HTTP.
          The ingestion service is never contacted, so it can stay asleep without
          affecting what you see here.
        </p>
        <dl className="mt-4 flex flex-wrap gap-x-8 gap-y-2 text-sm">
          <div>
            <dt className="text-zinc-500">Query</dt>
            <dd className="font-mono text-zinc-200">{result.ms} ms</dd>
          </div>
          <div>
            <dt className="text-zinc-500">Groups</dt>
            <dd className="font-mono text-zinc-200">
              {result.ok ? result.groups.length : "—"}
            </dd>
          </div>
          <div>
            <dt className="text-zinc-500">Status</dt>
            <dd className="font-mono text-zinc-200">
              {result.ok ? "ok" : "query failed"}
            </dd>
          </div>
        </dl>
      </section>

      {!result.ok && (
        <p className="rounded-lg border border-red-900/50 bg-red-950/30 px-4 py-3 text-sm text-red-300">
          The group query failed. Details are in the server log.
        </p>
      )}

      {result.ok && result.groups.length === 0 && (
        <p className="rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 text-sm text-zinc-400">
          No groups yet. Send an event to{" "}
          <code className="font-mono text-zinc-300">POST /api/events</code> on
          the ingestion service.
        </p>
      )}

      {result.ok && result.groups.length > 0 && (
        <ul className="space-y-2">
          {result.groups.map((group) => (
            <li key={group.id}>
              <Link
                href={`/groups/${group.id}`}
                className="block rounded-lg border border-zinc-800 bg-zinc-900/40 p-4 transition-colors hover:border-zinc-600 hover:bg-zinc-900/70"
              >
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span
                        className={`inline-flex shrink-0 rounded px-2 py-0.5 font-mono text-xs ring-1 ring-inset ${levelStyle(
                          group.level,
                        )}`}
                      >
                        {group.level}
                      </span>
                      <h3 className="truncate font-medium text-zinc-100">
                        {group.title}
                      </h3>
                    </div>
                    <p className="mt-1 truncate font-mono text-xs text-zinc-500">
                      {group.culprit ?? "no frame attributed"}
                    </p>
                    <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-zinc-500">
                      <span className="font-mono text-zinc-400">
                        {group.service}
                      </span>
                      <span>{group.platform}</span>
                      <span>first {relativeTime(group.first_seen)}</span>
                      {group.degraded_reason && (
                        <span className="rounded bg-amber-500/10 px-1.5 py-0.5 font-mono text-amber-300/90">
                          {group.degraded_reason}
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-mono text-lg text-zinc-100">
                      {group.event_count}
                    </div>
                    <div className="text-xs text-zinc-500">
                      {relativeTime(group.last_seen)}
                    </div>
                  </div>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  );
}
