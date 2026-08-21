/**
 * The read path talks to Postgres and to nothing else.
 *
 * That claim is what the whole architecture rests on: the dashboard renders in full while
 * the ingestion service is asleep, because it never asks it anything. It has been
 * measured by hand eight times and guarded by one grep over `web/`, and a grep is a poor
 * guard for it -- an alias (`const call = globalThis.fetch`) or a `node:https` import
 * walks straight past the pattern. Both were tried against it before this file was
 * written, and both got through.
 *
 * So these two tests watch for the attempt rather than for the spelling of it.
 *
 * ## Why the assertion is on what was recorded, not on what threw
 *
 * Pages catch their own errors: `load()` wraps its query in a try/catch and logs the
 * failure rather than letting it reach the reader, which is correct of it. A page that
 * did `try { await fetch(collector) } catch {}` would therefore render perfectly while
 * quietly depending on a service that is supposed to be optional. Only the record of the
 * attempt gives that away, so the assertion is that the record is empty -- never that
 * something threw.
 */
import { test } from "node:test";
import assert from "node:assert/strict";
import net from "node:net";

import Alerts from "@/app/alerts/page";
import Detectors from "@/app/detectors/page";
import Groups from "@/app/page";
import GroupPage from "@/app/groups/[id]/page";
import { aDetail, aGroup, anAlert, aDetector, reset, scenario } from "./fixtures";
import { render } from "./render";

/**
 * Records every outbound connection and refuses it.
 *
 * At the socket rather than at `fetch`, because that is the one layer everything has to
 * come through: `fetch` reaches it via undici, `node:https` reaches it directly, and an
 * alias taken before this runs still ends up here.
 */
function watchTheSocket() {
  const attempts: string[] = [];
  const inherited = net.Socket.prototype.connect;

  net.Socket.prototype.connect = function (...args: unknown[]) {
    const first = args[0];
    const target =
      typeof first === "object" && first !== null
        ? ((first as { host?: string; path?: string }).host ??
          (first as { path?: string }).path ??
          "unknown")
        : String(first);
    attempts.push(target);
    throw new Error(`the read path opened a connection to ${target}`);
  } as typeof net.Socket.prototype.connect;

  return {
    attempts,
    release: () => {
      net.Socket.prototype.connect = inherited;
    },
  };
}

test("rendering every page opens no connection to anything", async () => {
  reset();
  scenario.groups = [aGroup()];
  scenario.detail = aDetail();
  scenario.alerts = [anAlert()];
  scenario.detectors = [aDetector()];
  scenario.navCounts = { open_groups: 1, alerts: 1 };

  const socket = watchTheSocket();
  try {
    await render(await Groups({ searchParams: Promise.resolve({}) }));
    await render(await Alerts());
    await render(await Detectors());
    await render(
      await GroupPage({
        params: Promise.resolve({ id: "1" }),
        searchParams: Promise.resolve({}),
      }),
    );
  } finally {
    socket.release();
  }

  assert.deepEqual(
    socket.attempts,
    [],
    `a page reached out to ${socket.attempts.join(", ")}; the dashboard renders while the ingestion service is asleep only for as long as it asks it nothing`,
  );
});

test("the query layer asks the database and no other host", async () => {
  // The gap the test above cannot see. It renders with the query module replaced, so a
  // call added inside `lib/queries` would never run. This one loads the real module and
  // calls every export, recording the URLs rather than the sockets: a made-up host fails
  // at name resolution before a socket is ever opened, and the point is which host was
  // asked for.
  const DATABASE_URL =
    "postgresql://reader:secret@ep-test-000000-pooler.eu-central-1.aws.neon.tech/db";
  process.env.DATABASE_URL = DATABASE_URL;

  const asked: string[] = [];
  const inherited = globalThis.fetch;
  globalThis.fetch = ((input: Parameters<typeof fetch>[0]) => {
    const url =
      typeof input === "string" ? input : input instanceof URL ? input.href : String(input);
    asked.push(new URL(url).host);
    // Rejected rather than answered: the question is which host was asked for, and
    // nothing here should be able to reach one.
    return Promise.reject(new Error("blocked"));
  }) as typeof fetch;

  try {
    // Relative on purpose. The resolver points `@/lib/queries` at the fixtures, which is
    // what makes the render tests work and exactly what this test must not get: it is
    // here to watch the real module.
    const queries = await import("../../lib/queries.js");
    const calls = [
      () => queries.listGroups({ service: null, status: null, q: null }, null, 25),
      () => queries.countsByStatus({ service: null, status: null, q: null }),
      () => queries.getOverviewTrend({ service: null, status: null, q: null }, "7d"),
      () => queries.listServices(),
      () => queries.listSparklines([1]),
      () => queries.getStorageStatus(),
      () => queries.getGroup(1),
      () => queries.getGroupSeries(1, "24h"),
      () => queries.findSimilarGroups(1, "title"),
      () => queries.listAlerts(),
      () => queries.getDetectorScorecard(),
      () => queries.getNavCounts(),
    ];

    for (const call of calls) {
      await call().catch(() => undefined);
    }
  } finally {
    globalThis.fetch = inherited;
    delete process.env.DATABASE_URL;
  }

  // Neon's HTTP driver posts to the API host derived from the connection string.
  const expected = `api.${new URL(DATABASE_URL).host.replace(/^[^.]+\./, "")}`;
  const strangers = [...new Set(asked)].filter((host) => host !== expected);

  assert.ok(asked.length > 0, "no query reached the driver at all; this test proved nothing");
  assert.deepEqual(
    strangers,
    [],
    `the query layer asked ${strangers.join(", ")} as well as the database`,
  );
});
