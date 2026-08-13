import Link from "next/link";
import { listAlerts, type Alert } from "@/lib/queries";
import { relativeTime } from "@/lib/format";
import { Shell } from "@/app/components/shell/shell";
import { Panel } from "@/app/components/ui/panel";
import { AlertKindBadge } from "@/app/components/ui/badge";

export const dynamic = "force-dynamic";

export const metadata = { title: "Alerts — Stacklight" };

/**
 * `disabled` is written when an alert is raised with no mail configured, and it is never
 * revisited: turning mail on later must not deliver a backlog of everything that already
 * happened. So the note is in the past tense. The present tense read as a claim about
 * the deployment now, which stopped being true the moment mail was configured, while
 * these rows kept saying it about alerts raised weeks earlier.
 */
const DELIVERY_NOTES: Record<string, string> = {
  sent: "emailed",
  pending: "queued",
  failed: "delivery gave up",
  disabled: "recorded only, mail was not configured at the time",
};

function AlertRow({ alert }: { alert: Alert }) {
  return (
    <li>
      <Link
        href={`/groups/${alert.group_id}`}
        className="flex items-start gap-3 rounded-xl border border-edge bg-surface-1 p-4 transition-colors hover:border-edge-strong hover:bg-surface-2"
      >
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <AlertKindBadge kind={alert.kind} />
            <span className="font-mono text-xs text-ink-low">
              {alert.service}
            </span>
            <h3 className="min-w-0 flex-1 truncate text-sm text-ink-hi">
              {alert.title}
            </h3>
          </div>

          {alert.detector && (
            <p className="mt-1.5 font-mono text-[11px] text-ink-low">
              {alert.detector} &middot; {alert.observed} this hour against a
              baseline of {alert.baseline?.toFixed(2)} &middot; score{" "}
              {alert.score?.toFixed(2)}
            </p>
          )}

          <p className="mt-1 text-[11px] text-ink-faint">
            {DELIVERY_NOTES[alert.delivery_state] ?? alert.delivery_state}
            {alert.delivery_attempts > 0 &&
              ` · ${alert.delivery_attempts} attempt${alert.delivery_attempts === 1 ? "" : "s"}`}
            {alert.last_error && ` · ${alert.last_error}`}
          </p>
        </div>

        <span className="shrink-0 text-[11px] text-ink-low">
          {relativeTime(alert.created_at)}
        </span>
      </Link>
    </li>
  );
}

/**
 * Query and its timing together, outside the component.
 *
 * Not a style preference: Date.now() in a component body is a call to an impure
 * function during render, and the lint rules reject it.
 */
async function load(): Promise<{ alerts: Alert[]; failed: boolean; ms: number }> {
  const started = Date.now();
  try {
    const alerts = await listAlerts();
    return { alerts, failed: false, ms: Date.now() - started };
  } catch (error) {
    console.error("alert query failed", error);
    return { alerts: [], failed: true, ms: Date.now() - started };
  }
}

export default async function Page() {
  const { alerts, failed, ms } = await load();

  return (
    <Shell current="alerts" queryMs={ms}>
      <header className="mb-6">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          Alerts
        </h1>
        <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-low">
          An alert is a row before it is an email. It is written in the same
          transaction as the event that caused it, so nothing about a failed
          delivery, an unreachable mail server, or an instance going to sleep
          mid-send can lose it.
        </p>
      </header>

      {/*
        Its own tinted panel rather than body text. This is the most important
        paragraph on the page: it says the feature does not do the thing most
        people assume an alerting feature does.
      */}
      <Panel className="mb-6 border-warn-edge bg-warn-bg">
        <h2 className="text-[10px] font-medium uppercase tracking-[0.09em] text-warn">
          Latency, honestly
        </h2>
        <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink">
          Detection runs when an event arrives, because that is the only moment
          this service is reliably running &mdash; it sleeps after fifteen idle
          minutes. So an alert is not raised the instant a spike begins; it is
          raised on the next event that reaches a woken instance, and the wake
          itself takes about a hundred seconds. A spike that starts and stops
          entirely within a quiet period is never seen at all.
        </p>
      </Panel>

      {failed && (
        <Panel className="border-danger-edge bg-danger-bg">
          <p className="text-sm text-danger">
            The alert query failed. Details are in the server log.
          </p>
        </Panel>
      )}

      {!failed && alerts.length === 0 && (
        <Panel>
          <p className="text-sm text-ink-low">
            Nothing has been worth an alert yet.
          </p>
        </Panel>
      )}

      {alerts.length > 0 && (
        <ul className="space-y-2">
          {alerts.map((alert) => (
            <AlertRow key={alert.id} alert={alert} />
          ))}
        </ul>
      )}
    </Shell>
  );
}
