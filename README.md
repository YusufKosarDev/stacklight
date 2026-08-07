# Stacklight

Error ingestion and triage for small services.

**Live:** [stacklight-eosin.vercel.app](https://stacklight-eosin.vercel.app)

**Status: step 1.** Events are grouped into distinct faults by a deterministic
fingerprint. Time series, alerting and a client library land in later steps.

---

## Grouping

Ten thousand events are not ten thousand problems. Grouping turns the stream
into a list of distinct faults, and the only useful version of that is one you
can predict: the same error must always land in the same group, and you have to
be able to see why it did.

Nothing in the pipeline is statistical, trained, or free to change its mind
between two runs. Every group page shows its own worked example, and
[/how-grouping-works](https://stacklight-eosin.vercel.app/how-grouping-works)
explains the rules.

```
event ──▶ detect platform ──▶ parse frames ──▶ split in-app / vendor
                                                       │
                             normalize message ────────┤
                                                       ▼
                                          assemble fingerprint input
                                                       │
                                                    SHA-256
                                                       ▼
                                     group = (fingerprint, version)
```

**One parser per platform.** Java and V8 stack traces share nothing past the
leading `at`, and the rule for deciding whether a frame belongs to the
application differs for each, so each gets its own implementation behind a
common interface.

**Only in-app frames decide the group.** One bug is reached through a different
framework path depending on which request hit it. Grouping on vendor frames
splits a single fault across many groups. Measured live: the same
`NullPointerException` arriving through `InvocableHandlerMethod` and through
`RequestMappingHandlerAdapter` produces one group.

**Line numbers are excluded.** Adding a line above a throw site shifts every
number below it, and a fingerprint that moved on every such edit would open a
new group for an error that never changed.

**Paths are cut at the last source root.** `/app/src/cart.js` in a container and
`/home/dev/work/checkout/src/cart.js` on a laptop are the same frame. Without
this, the same fault files itself twice depending on where it ran.

**The message is excluded whenever frames exist.** Messages carry values and
runtimes reword them between releases. With no frames the normalized message is
all there is and is used instead — the group says so on its page.

**Minified frames are detected, not grouped on.** Minified names are reassigned
on every build, so a fingerprint built from them opens a fresh group per deploy,
which is worse than not grouping. Resolving them properly needs source maps,
which this project does not accept yet.

### Why old groups are frozen when the algorithm changes

A group is keyed by `(fingerprint, fingerprint_version)`. When the algorithm
changes, old groups are left exactly as they are.

The tempting alternative is to re-fingerprint history so everything lives under
the new rules. It does not survive contact with what a group actually is. A
group carries observed facts — when it was first seen, how often it happened,
and later whether someone resolved it. A new version can merge two old groups
and split a third in the same pass, so there is no one-to-one mapping to apply
and no correct answer for which first-seen survives a merge or what happens to
the group already marked resolved.

The cost is real and worth stating plainly: after a version bump, an error that
is still happening opens a new group while the old one stops growing. That is
visible noise, which is why the active version changes rarely and on purpose.

What makes the change safe to plan is that every group stores the exact text
that was hashed. A future version can be run over those stored inputs offline to
produce a merge-and-split report before it is ever made active.

### Similar groups

`pg_trgm` over a GIN index surfaces near misses on a group page. It catches what
a fingerprint deliberately will not: renaming `CartService.total` to
`PricingService.calculateTotal` is a different code path and correctly gets its
own group, but the two are shown as related at a similarity of 1.00.

They are suggestions. Nothing merges on its own, and no model is involved.

---

## The architectural bet

```
                  write path                              read path
                  ----------                              ---------

  client                                            browser
    |                                                  |
    | POST /api/events                                 | GET /
    | X-Stacklight-Key                                 |
    v                                                  v
  Spring Boot 4 (Render, free)                    Next.js 16 (Vercel)
    |  Flyway migration                            server component
    |  JdbcClient insert                                |
    v                                                   v
  Neon Postgres 17  <----------- SQL over HTTP ---------+
  (eu-central-1)
```

The read path never touches the ingestion service.

That is the whole architectural bet. Render's free tier sleeps after 15 minutes
of inactivity and takes 30–60 seconds to wake. If the dashboard were proxied
through it, every visitor arriving at a cold service would stare at a blank page
for the better part of a minute. Because the dashboard is a server component
talking to Postgres directly, the ingestion service can be asleep, restarting or
failing to deploy and the dashboard still renders in full.

The measurements below test that claim rather than assert it, and it is
re-checked whenever the read path changes.

---

## Connection strategy

Both halves talk to the same database, and they deliberately do it differently.

| | Ingestion (Spring Boot) | Dashboard (Next.js) |
|---|---|---|
| Neon endpoint | direct | pooled (`-pooler`) |
| Driver | pgjdbc + HikariCP | `@neondatabase/serverless`, HTTP |
| Role | `neondb_owner` (read/write, runs migrations) | `stacklight_web` (`SELECT` only) |
| Connections held | up to 3, drains to 0 when idle | none |

**Why the dashboard uses SQL over HTTP.** Neon's free plan bills compute in
CU-hours and only suspends the compute once no client is connected. A TCP pool
in a serverless function would keep the compute awake around the clock and
exhaust the monthly allowance. The HTTP driver issues one-shot queries and holds
nothing open, so the compute really does scale to zero between visits.

**Why the ingestion service uses the direct endpoint.** Neon's pooled endpoint
is PgBouncer in transaction mode, which conflicts with the server-side prepared
statements pgjdbc starts using after a few executions. With a single Render
instance and a pool capped at three, there is nothing for a pooler to solve.

**Why the pool drains to zero.** `minimum-idle: 0`, `idle-timeout: 30s` and
`keepalive-time: 0` together let the last connection close shortly after traffic
stops. A keepalive would poke the database on a timer and quietly prevent
suspension — the same CU-hour leak in a different disguise.

**Why `/actuator/health` does not check the database.** It is the uptime-ping
target. A database check on every ping would keep the Neon compute awake for
exactly the reason above.

---

## Layout

```
backend/   Spring Boot 4.1 (Java 21), deployed to Render from Dockerfile
  grouping/  parsers, normalizer, fingerprinters, version registry
  ingest/    endpoint, guard, event and group persistence
web/       Next.js 16 App Router, deployed to Vercel
  app/       group list, group detail, how grouping works
  lib/       Neon handle and the read queries
.githooks/ commit-msg policy, enabled with core.hooksPath
.github/   CI: policy scan, backend tests, image build, web build
```

Grouping runs inline on the ingest path rather than behind a queue: it is a few
regular expressions and a hash followed by one upsert, so the latency it adds is
small next to the network call that delivered the event, and a group is visible
the moment its first event lands. A worker earns its complexity when volume
outgrows a single free instance, not before.

## API

`POST /api/events` — requires header `X-Stacklight-Key`.

```json
{
  "eventId": "3f1c9d2e-...",
  "service": "checkout-api",
  "level": "ERROR",
  "message": "Cannot invoke \"String.length()\" because \"promoCode\" is null",
  "platform": "java",
  "exceptionType": "java.lang.NullPointerException",
  "stacktrace": "java.lang.NullPointerException\n\tat com.example...",
  "payload": { "release": "1.4.0" }
}
```

Only `service`, `level` and `message` are required. Everything feeding grouping
is optional: a caller sending nothing but a message still gets a group, just a
coarser one, and the group reports why. `platform` is detected from the trace
when absent, and `payload.stacktrace` is still read as an array of lines for
clients written before grouping existed.

`eventId` is optional; the server generates one when it is missing. A repeated
`eventId` is discarded by a unique constraint rather than surfacing as an error.
The group counter is only touched after the event row is written, so a client
retrying a delivery it already made cannot inflate it:

```json
{ "eventId": "3f1c9d2e-...", "stored": true,
  "fingerprint": "b2b9c15ea9557bba93353505c471e919", "groupId": 1 }
```

`GET /actuator/health` — no authentication, no database access.

---

## Local development

```bash
git config core.hooksPath .githooks   # once per clone

cd backend && ./mvnw verify           # needs a running Docker daemon
cd web && npm ci && npm run dev
```

Backend tests start a real PostgreSQL 17 through Testcontainers and run the
actual Flyway migration, so schema and SQL problems surface locally instead of
on Neon.

`npm run build` deliberately succeeds without `DATABASE_URL`: the dashboard must
never reach the database at build time, and CI enforces it.

---

## Deployment

Environment variables are set in the Render and Vercel dashboards. Nothing
secret is committed; see `backend/.env.example` and `web/.env.example` for the
shape.

| Service | Key |
|---|---|
| Render | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `INGEST_API_KEY`, `JAVA_TOOL_OPTIONS` |
| Vercel | `DATABASE_URL` |

Render sets `PORT` itself, and the application reads it.

---

## Measured results

Measured against the live deployment on 7 August 2026. Ingestion on Render
(Frankfurt, free), dashboard on Vercel (`fra1`), database on Neon
(`eu-central-1`).

### Grouping, on the live deployment

| Check | Result |
|---|---|
| Same fault, line numbers 42 → 57, different release | one group |
| Same fault, different UUID in the message | one group |
| Same fault through two different framework paths | one group |
| Same JS fault, container path vs laptop path | one group |
| Renamed method, otherwise identical fault | separate group, similarity **1.00** |
| Event with no stack trace | grouped, flagged `no_frames` |
| Minified JS frames | grouped, flagged `minified` |
| Same `event_id` sent twice | one row, counter **not** incremented |

### Pipeline

| Check | Result |
|---|---|
| `POST /api/events` writes a row | 202, `stored: true` |
| Request without the shared secret | 401 |
| Dashboard renders groups | 5 groups, 12 events |
| **Dashboard while ingestion is asleep** | **200 in 0.36 s, full data** |
| Group list response time | 0.36–0.51 s warm |
| Group detail, including the similarity query | 0.35–0.45 s |
| First request after a fully idle period | 5.6 s (see below) |
| Neon query time from `fra1` | 6–12 ms |
| Ingestion cold start | **95 s, 104 s, 114 s**, measured three times |
| Ingestion when warm | 0.19–0.26 s |
| `stacklight_web` privileges | `SELECT` succeeds, `INSERT` denied |

### The claim, and the control that backs it

The dashboard measurements above were taken 19 minutes after the last request to
the ingestion service, and re-run after grouping shipped because step 1 added
queries the read path did not have before, including a lateral join and a
trigram search.

To confirm the service was genuinely asleep rather than merely idle, the next
request after the measurement was timed: **104 seconds**. So during the same
window in which the ingestion service could not answer at all, the dashboard
served complete data — group list, in-app frame breakdown, fingerprint input and
similar groups — in about a third of a second.

That is the architectural bet, tested rather than asserted.

### The read path has its own cold start, and it is not free

The very first dashboard request after a long idle period took **5.6 seconds**;
every request after it took under half a second. That is the Neon compute waking
from scale-to-zero, stacked on a cold Vercel function.

This is the price of the connection strategy above, and it is worth paying here:
5.6 seconds once, against a compute kept awake around the clock and a monthly
CU-hour allowance spent doing nothing. It is named rather than hidden because a
visitor who arrives first does feel it.

### Two findings worth carrying forward

**Cold starts are worse than the platform documents.** Render describes roughly
a minute; the three measurements here were 95, 104 and 114 seconds. The first of
them returned **503** rather than waiting — the platform gave up before the
service finished starting.

This decides the shape of the client library in a later step. A caller must
never block on this endpoint, and a single failed attempt cannot be treated as a
lost event: the SDK needs an async bounded queue with retry and backoff, and the
first delivery after an idle period should be expected to fail.

**The two halves fail independently.** Nothing above required the ingestion
service to be reachable for the dashboard to work, or the reverse. That is the
property the rest of the project is built on.
