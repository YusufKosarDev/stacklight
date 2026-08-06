import { sql } from "@/lib/db";

// Rendered per request. Without this the build would try to prerender the page
// and reach for the database at build time.
export const dynamic = "force-dynamic";

type EventRow = {
  event_id: string;
  received_at: string;
  service: string;
  level: string;
  message: string;
};

type LoadResult =
  | { ok: true; rows: EventRow[]; ms: number }
  | { ok: false; ms: number };

async function loadEvents(): Promise<LoadResult> {
  const started = Date.now();

  try {
    const rows = (await sql()`
      select event_id,
             to_char(received_at at time zone 'utc', 'YYYY-MM-DD HH24:MI:SS') as received_at,
             service,
             level,
             message
        from events
       order by received_at desc
       limit 50
    `) as EventRow[];

    return { ok: true, rows, ms: Date.now() - started };
  } catch (error) {
    // The driver error can carry the host and role from the connection string,
    // so it stays in the server log and never reaches the page.
    console.error("events query failed", error);
    return { ok: false, ms: Date.now() - started };
  }
}

const LEVEL_STYLES: Record<string, string> = {
  ERROR: "bg-red-500/10 text-red-300 ring-red-500/30",
  WARN: "bg-amber-500/10 text-amber-300 ring-amber-500/30",
  INFO: "bg-sky-500/10 text-sky-300 ring-sky-500/30",
};

function levelStyle(level: string) {
  return (
    LEVEL_STYLES[level.toUpperCase()] ??
    "bg-zinc-500/10 text-zinc-300 ring-zinc-500/30"
  );
}

export default async function Page() {
  const result = await loadEvents();

  return (
    <main className="mx-auto w-full max-w-5xl flex-1 px-6 py-12">
      <header className="mb-8">
        <h1 className="text-2xl font-semibold tracking-tight text-zinc-100">
          Stacklight
        </h1>
        <p className="mt-1 text-sm text-zinc-400">
          Step 0 &mdash; deployment pipeline proof. No fingerprinting, grouping
          or alerting yet.
        </p>
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
            <dt className="text-zinc-500">Rows</dt>
            <dd className="font-mono text-zinc-200">
              {result.ok ? result.rows.length : "—"}
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
          The events query failed. Details are in the server log.
        </p>
      )}

      {result.ok && result.rows.length === 0 && (
        <p className="rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 text-sm text-zinc-400">
          No events yet. Send one to{" "}
          <code className="font-mono text-zinc-300">POST /api/events</code> on
          the ingestion service.
        </p>
      )}

      {result.ok && result.rows.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-zinc-800">
          <table className="w-full min-w-[42rem] border-collapse text-left text-sm">
            <thead>
              <tr className="border-b border-zinc-800 bg-zinc-900/60 text-xs uppercase tracking-wider text-zinc-500">
                <th className="px-4 py-3 font-medium">Received (UTC)</th>
                <th className="px-4 py-3 font-medium">Level</th>
                <th className="px-4 py-3 font-medium">Service</th>
                <th className="px-4 py-3 font-medium">Message</th>
              </tr>
            </thead>
            <tbody>
              {result.rows.map((row) => (
                <tr
                  key={row.event_id}
                  className="border-b border-zinc-800/60 last:border-0"
                >
                  <td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-zinc-400">
                    {row.received_at}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`inline-flex rounded px-2 py-0.5 font-mono text-xs ring-1 ring-inset ${levelStyle(
                        row.level,
                      )}`}
                    >
                      {row.level}
                    </span>
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-zinc-300">
                    {row.service}
                  </td>
                  <td className="px-4 py-3 text-zinc-200">{row.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}
