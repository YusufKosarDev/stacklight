import Link from "next/link";
import { listAlerts, type Alert } from "@/lib/queries";
import { relativeTime } from "@/lib/format";

export const dynamic = "force-dynamic";

export const metadata = { title: "Alerts — Stacklight" };

const KIND_STYLES: Record<string, { label: string; icon: string; className: string }> = {
  spike: {
    label: "spike",
    icon: "▲",
    className: "bg-amber-500/10 text-amber-300 ring-amber-500/30",
  },
  new_group: {
    label: "new error",
    icon: "✦",
    className: "bg-sky-500/10 text-sky-300 ring-sky-500/30",
  },
  regression: {
    label: "regression",
    icon: "↺",
    className: "bg-red-500/15 text-red-300 ring-red-500/40",
  },
};

const DELIVERY_NOTES: Record<string, string> = {
  sent: "emailed",
  pending: "queued",
  failed: "delivery gave up",
  disabled: "recorded only, mail not configured",
};

function AlertRow({ alert }: { alert: Alert }) {
  const kind = KIND_STYLES[alert.kind] ?? KIND_STYLES.spike;

  return (
    <li>
      <Link
        href={`/groups/${alert.group_id}`}
        className="block rounded-lg border border-zinc-800 bg-zinc-900/40 p-4 transition-colors hover:border-zinc-600 hover:bg-zinc-900/70"
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span
                className={`inline-flex shrink-0 items-center gap-1 rounded px-2 py-0.5 font-mono text-xs ring-1 ring-inset ${kind.className}`}
              >
                <span aria-hidden>{kind.icon}</span>
                {kind.label}
              </span>
              <span className="font-mono text-xs text-zinc-400">
                {alert.service}
              </span>
              <h3 className="truncate text-sm text-zinc-100">{alert.title}</h3>
            </div>

            {alert.detector && (
              <p className="mt-1 font-mono text-xs text-zinc-500">
                {alert.detector} &middot; {alert.observed} this hour against a
                baseline of {alert.baseline?.toFixed(2)} &middot; score{" "}
                {alert.score?.toFixed(2)}
              </p>
            )}

            <p className="mt-1 text-xs text-zinc-600">
              {DELIVERY_NOTES[alert.delivery_state] ?? alert.delivery_state}
              {alert.delivery_attempts > 0 &&
                ` · ${alert.delivery_attempts} attempt${alert.delivery_attempts === 1 ? "" : "s"}`}
              {alert.last_error && ` · ${alert.last_error}`}
            </p>
          </div>

          <div className="shrink-0 text-right text-xs text-zinc-500">
            {relativeTime(alert.created_at)}
          </div>
        </div>
      </Link>
    </li>
  );
}

export default async function Page() {
  let alerts: Alert[] = [];
  let failed = false;
  try {
    alerts = await listAlerts();
  } catch (error) {
    console.error("alert query failed", error);
    failed = true;
  }

  return (
    <main className="mx-auto w-full max-w-4xl flex-1 px-6 py-12">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Link
            href="/"
            className="text-sm text-zinc-400 transition-colors hover:text-zinc-200"
          >
            &larr; All groups
          </Link>
          <h1 className="mt-4 text-2xl font-semibold tracking-tight text-zinc-100">
            Alerts
          </h1>
        </div>
        <Link
          href="/detectors"
          className="rounded-md border border-zinc-700 px-3 py-1.5 text-sm text-zinc-300 transition-colors hover:border-zinc-500 hover:text-zinc-100"
        >
          Detector scorecard
        </Link>
      </div>

      <p className="mt-2 text-sm leading-relaxed text-zinc-400">
        An alert is a row before it is an email. It is written in the same
        transaction as the event that caused it, so nothing about a failed
        delivery, an unreachable mail server, or an instance going to sleep
        mid-send can lose it.
      </p>

      <section className="mt-6 rounded-lg border border-zinc-800 bg-zinc-900/40 p-4">
        <h2 className="text-xs font-medium uppercase tracking-wider text-zinc-500">
          Latency, honestly
        </h2>
        <p className="mt-2 text-sm leading-relaxed text-zinc-400">
          Detection runs when an event arrives, because that is the only moment
          this service is reliably running &mdash; it sleeps after fifteen idle
          minutes. So an alert is not raised the instant a spike begins; it is
          raised on the next event that reaches a woken instance, and the wake
          itself takes about a hundred seconds. A spike that starts and stops
          entirely within a quiet period is never seen at all.
        </p>
      </section>

      {failed && (
        <p className="mt-6 rounded-lg border border-red-900/50 bg-red-950/30 px-4 py-3 text-sm text-red-300">
          The alert query failed. Details are in the server log.
        </p>
      )}

      {!failed && alerts.length === 0 && (
        <p className="mt-6 rounded-lg border border-zinc-800 bg-zinc-900/40 px-4 py-3 text-sm text-zinc-400">
          Nothing has been worth an alert yet.
        </p>
      )}

      {alerts.length > 0 && (
        <ul className="mt-6 space-y-2">
          {alerts.map((alert) => (
            <AlertRow key={alert.id} alert={alert} />
          ))}
        </ul>
      )}
    </main>
  );
}
