# Stacklight

Error ingestion and triage for small services.

**Live:** [stacklight-eosin.vercel.app](https://stacklight-eosin.vercel.app)

**Status: step 4.** Events are grouped into distinct faults, counted into an
hourly trend that outlives them, kept inside a storage budget that would
otherwise take the project down, watched by three detectors whose relative merits
are measured rather than assumed, and delivered by clients written around a
collector that sleeps.

---

## The clients

Two of them — Java and Node — and the shape of both was decided by a measurement
rather than a preference. From step 0:

> The collector's cold start was measured at 95, 104, 104, 106 and 114 seconds.
> On the 95-second measurement the platform returned **503** rather than holding
> the connection open.

Everything below follows from that. A client that waits on this endpoint is worse
than no client at all, and a client that treats one failed attempt as a lost
event throws away almost everything during a wake.

### What both clients do

| | |
|---|---|
| **Capture** | Enqueue and return. No connection is opened, none is waited for, nothing is thrown — the caller is usually already dealing with something that went wrong. Measured at **19.8 microseconds** per capture against a collector that does not answer. |
| **Queue** | Bounded. The alternative is not "no loss", it is an application that runs out of heap because its error reporter would not let go of anything. |
| **When full** | The **oldest** goes. The queue only fills when the collector is unreachable, so when it comes back, what is happening now is worth more than what was happening two minutes ago. |
| **Every drop** | Counted and readable from the application. A reporter that quietly loses things is worse than one that says how many. |
| **Timeouts** | Short — 3 s connect, 5 s request. The first attempt against a sleeping collector is *supposed* to fail. |
| **Retries** | Exponential backoff with **full jitter**, capped at 30 s. Failed batches go back to the front of the queue rather than being counted as sent. |
| **Rejections** | A 401 does not become a 200 by being retried. Those batches are discarded and the reason is kept, instead of blocking the queue for ever. |
| **Shutdown** | Bounded flush. The events worth having most are the ones still in memory when a process dies. |
| **Its own failures** | Silent unless `debug` is on. A library whose job is to report errors must not become a source of them. |
| **Dependencies** | **None.** Every jar or package a reporter drags in is a version it can conflict with, inside an application that did not ask for it. |

### Why the drop policy is what it is

Dropping the oldest means the first occurrence of an incident is the first to go,
and that is the diagnostic one. It is accepted because the collector groups by
fingerprint: a later event of the same fault opens the same group, so losing the
earliest copy costs a count and a timestamp. Dropping the *newest* instead would
mean that during a sustained burst nothing recent ever gets through, which is
worse in the case that actually matters.

### Stack traces are sent exactly as the runtime printed them

The Java client sends `printStackTrace` output; the Node client sends
`error.stack`. Neither reformats anything, because the collector's parsers were
written against those formats in step 1 — tidying them here would mean two
formats to keep in step instead of one.

That compatibility is a test rather than an assumption. Samples captured from a
running JVM and from Node live in the collector's suite, and they assert the
frames come out right, the vendor split is right, and a redeploy that shifts line
numbers keeps the same group.

### Java

Two artifacts. `stacklight-client` has no compile dependencies and works from
plain Java; `stacklight-spring` is a thin starter on top.

```java
StacklightClient client = StacklightClient.start(new StacklightOptions()
        .endpoint("https://collector.example.com/api/events")
        .apiKey(System.getenv("STACKLIGHT_KEY"))
        .service("checkout-api")
        .release("1.4.0"));

client.capture(exception);
```

With the starter, two properties and nothing else:

```yaml
stacklight:
  endpoint: ${STACKLIGHT_ENDPOINT:}
  api-key: ${STACKLIGHT_KEY:}
```

Exceptions reaching a controller are reported through a `HandlerExceptionResolver`
that **always returns null**, rather than a `@ControllerAdvice`. An advice would
have to either handle the exception, taking over the application's error
responses, or rethrow it, changing where it surfaces. Returning null means "not
handled": it sees everything and changes nothing. Verified live — the demo's
failing endpoint still returns the application's own 500.

The uncaught-exception handler delegates to whatever was installed before it,
because a process-wide handler is something that belonged to the application.

### Node

Node only. The browser is deliberately out of scope: the collector authenticates
with a shared secret in a header, and putting that in browser JavaScript publishes
it — anyone could then fill the database, which on this plan suspends the project.
Supporting browsers needs a public-key model with per-origin limits, which is its
own piece of work rather than a flag on this one.

```js
const stacklight = StacklightClient.start({
  endpoint: process.env.STACKLIGHT_ENDPOINT,
  apiKey: process.env.STACKLIGHT_KEY,
  service: "checkout-api",
});

stacklight.captureException(error);
```

`uncaughtException` needs care and gets it. Registering a listener suppresses
Node's default behaviour of printing the error and exiting, so a reporter that
merely listened would silently turn a crash into a hang. The handler restores it
when nothing else has claimed it: if this client is the only listener, it flushes
and then exits with the code Node would have used.

### Running the examples

```bash
# Java
cd sdk/java && ./mvnw install
cd examples/java-demo && ./mvnw package
STACKLIGHT_ENDPOINT=... STACKLIGHT_KEY=... java -jar target/java-demo-0.1.0.jar
curl localhost:8081/boom

# Node
STACKLIGHT_ENDPOINT=... STACKLIGHT_KEY=... node examples/node-demo/demo.js
node examples/node-demo/overflow.js     # drop policy, no collector needed
```

Neither client is published to Maven Central or npm, and that is a decision rather
than an omission. Publishing means a namespace, signing keys and an account per
registry, none of which changes whether the code is any good; the repository and
`mvn install` are enough for what this is.

---

## Anomaly detection, and why the textbook answer does not fit

The obvious move is EWMA or a rolling z-score, both of which come from watching
metric time series. Before writing either, it was worth measuring what this data
actually looks like:

> **97% of group-hour buckets on this deployment are empty.**

That number decides everything downstream. EWMA and z-score both ask a question
of the form "how does this hour compare to the usual", and when the usual is
nothing, *two* errors is several times the baseline and many sigmas above the
mean. Both detectors are formally correct and completely useless: they fire on
the first error a group ever produces.

So all three detectors here sit behind an **absolute floor on the count**. It is
less a tuning parameter than an admission that ratios carry almost no
information down there, and a test demonstrates the point by lifting the floor
and watching every detector call a handful of errors a significant deviation.

There is a second guard for the same reason: a group with almost no history is
not judged statistically at all. A brand-new group producing six errors scores
12.0, 8.9 and 6.0 against thresholds of 3 — every detector would call it a
spike, and every one would be wrong, because it has no baseline to deviate from.
That case is covered by a rule that says what is actually true about it: this
group is new.

### The three detectors

| Detector | Rule | Where it breaks |
|---|---|---|
| `ewma` | Exponentially weighted baseline; fires at a multiple of it | Baseline sits near zero, so the multiple means little |
| `zscore` | Standard deviations above the trailing mean | Divides by the spread, so a bursty group desensitises the detector watching it |
| `poisson` | Upper-tail probability of the count under the rate recent hours imply | Ties variance to the rate, so a genuinely erratic group makes it over-fire |

Poisson is active because the shape of the data is a counting process — small
non-negative integers arriving in bursts — and it takes its spread from the rate
rather than needing to be told one per group. Six errors is remarkable for a
group that normally sees one and unremarkable for a group that normally sees
fifty, and no per-group tuning says so.

That is the argument. The scorecard is what decides whether the argument
survives contact with the data.

### Shadow mode

Every detector judges every bucket that clears the floor. Exactly one is allowed
to raise an alert; the others run in shadow and have their verdicts recorded
anyway — **including the ones that decline to fire**, since a detector that only
reported firings could never be measured for what it missed, and could improve
its record by becoming more timid.

Below the floor nothing is evaluated and nothing is recorded. No detector could
fire down there, so the history query and the three evaluations are skipped
outright — the same reasoning that makes the floor necessary also makes it a
cheap short circuit. The consequence is worth naming: the scorecard's true
negatives are drawn only from hours that reached the floor, not from the 97% of
buckets that are empty. The scorer applies the same floor, so the comparison
between detectors stays consistent; it is the absolute counts that would be
flattered by reading them as "out of every hour that ever happened".

Because they see identical input at the same moment, changing the active
detector is a configuration change whose effect was measured before it was made.

### Self-scoring, and what it is not

Old verdicts are revisited once there is hindsight to judge them by. An hour
counts as a genuine surge when its count stands clear of the rate around it,
measured from **both** sides — information the detector was not allowed to have,
which is the only reason the scorer can disagree with it. Each verdict then
falls into one of four boxes and precision and recall follow.

**This is not accuracy, and the dashboard says so in the same words.** Nobody
labels these hours by hand. The reference is a rule, so the numbers measure
agreement with a rule applied in hindsight, and a detector could score well by
agreeing with a rule that is itself wrong. What they are good for is comparing
detectors against each other on identical data, which is the question being
asked. They are shown unfiltered, including where a shadow beats the detector in
charge.

### Alert delivery

An alert is a row before it is an email. It is written in the same transaction
as the event that caused it, so a failed send, an unreachable mail server, or an
instance sleeping mid-send cannot lose one. Delivery is a best-effort drain
afterwards, triggered on the same two signals retention uses.

Without mail configured, alerts are recorded as `disabled` rather than queued —
so setting mail up later does not fire a backlog of everything that ever
happened.

One alert per group per cooldown. A group in the middle of a spike produces
events continuously, and an alert per event is how a mailbox teaches somebody to
filter the whole feature into a folder they never open.

### ⚠️ Alert latency, stated plainly

Detection runs when an event arrives, because that is the only moment this
service is reliably running. The consequences are real and not hidden:

- An alert is not raised when a spike begins. It is raised on the next event
  that reaches a **woken** instance, and waking takes about a hundred seconds.
- **A spike that begins and ends entirely within a quiet period is never seen.**
  Nothing arrives to trigger an evaluation, so nothing evaluates.
- A drop to zero cannot be detected at all, by construction. Absence of events
  is absence of triggers.

A scheduler would not fix this. It would fire into a process that is not
running, which is the failure retention already ran into in step 2 — the
difference being that retention can catch up on waking, and a missed spike
cannot be caught up on.

---

## Volume, and the limit that ends the project

The free database plan does not bill for going over 512 MB. It suspends the
project. So retention is not housekeeping here, it is the thing standing between
this deployment and being switched off, and it has to work on an instance that
sleeps after fifteen idle minutes.

### Why there is no scheduler

The obvious design is a nightly job. It does not work, and the failure is quiet:
a timer firing into a process that is not running does not error, it simply does
not happen. Storage grows, nothing complains, and the first symptom is a
suspended database.

The way out is an observation rather than a workaround:

> **Storage only grows when events arrive, and events can only arrive while the
> service is awake.**

So retention does not need a clock. It needs to run in proportion to ingest —
which is exactly the thing it is cleaning up after. Sweeps are triggered from
the ingest path, amortised so that most events pay nothing, plus once at startup
to clear whatever piled up during a long absence.

Every pass is bounded to one batch, so falling behind costs more passes rather
than one long one, and a sweep can never turn into a stall that outlives the
request that triggered it.

### Three defences, in order

| Defence | What it does |
|---|---|
| **Retention window** | Raw events are deleted after 14 days. Rollups are kept for good. |
| **Adaptive window** | Past 300 MB the window drops to 7 days, past 400 MB to 3. It widens again on its own once the pressure is gone. |
| **Hourly cap** | Past 200 events an hour, a group keeps being counted but stops storing detail. |

The cap is worth spelling out, because the naive version of it is a lie: dropping
events under load makes a burst read as a **dip**, exactly when the chart matters
most. Here the rollup is incremented before the decision is made, so the trend
stays complete and only the stack traces behind part of it are gone. The group
page says so rather than letting you read a chart that is quietly thinner than it
looks.

### Rollups, and why the trend outlives the events

Counts are written inline, in the same transaction as the event, for the same
reason grouping is: a count that happens on arrival cannot miss.

They live in their own table rather than being computed from `events`, and that
is the whole point. A chart read from raw events would go flat the moment
retention ran — precisely where the history stops being reconstructible and
starts being worth having. A tested guarantee, not an intention: the suite
deletes every event behind a trend and asserts the trend is unchanged.

---

## Regression detection

A group is `open`, `resolved`, `ignored` or `regressed`. An event landing on a
group somebody called resolved means the fix did not hold — a different
situation from one nobody has looked at yet, so it gets its own state instead of
being quietly reopened, and it records which build brought it back next to the
one it was fixed in.

Only the event that ends the resolved state is reported as a regression. Reading
the group's status after the update cannot tell that event apart from the
hundred after it, so the status is read in the same statement, before the upsert
touches it.

**Status changes go through the ingestion service, not the dashboard.** The
dashboard reads Postgres with a role holding nothing but `SELECT`, which is what
lets it keep working while the ingestion service is asleep. Supporting one button
would mean granting that role write access to the schema. The trade is taken
knowingly: marking a group resolved can wait out a cold start, reading the
dashboard cannot.

```
PATCH /api/groups/{id}   {"status": "resolved", "release": "1.7.0"}
```

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
  grouping/   parsers, normalizer, fingerprinters, version registry
  ingest/     endpoint, guard, event / group / rollup persistence, status
  retention/  sweep, adaptive window, startup catch-up
  detection/  three detectors, shadow recording, self-scoring
  alerting/   outbox, cooldown, best-effort mail delivery
web/       Next.js 16 App Router, deployed to Vercel
  app/        groups, charts, alerts, detector scorecard, how grouping works
  lib/        Neon handle and the read queries
.githooks/ commit-msg policy, enabled with core.hooksPath
.github/   CI: policy scan, backend tests, image build, web build
```

Grouping and rollup both run inline on the ingest path rather than behind a
queue: a few regular expressions, a hash and two upserts, so the latency they add
is small next to the network call that delivered the event, and a group and its
trend are visible the moment the first event lands. A worker earns its complexity
when volume outgrows a single free instance, not before.

Retention is the exception that proves the rule — it is the one job that would
normally be scheduled, and it is not, for the reason given above.

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
| Render, optional | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `ALERT_EMAIL_TO`, `ALERT_EMAIL_FROM`, `DASHBOARD_URL`, `DETECTOR_ACTIVE` |
| Vercel | `DATABASE_URL` |

Render sets `PORT` itself, and the application reads it.

The mail variables are optional in the strict sense: with none of them set,
detection still runs and alerts are still recorded, they are simply marked
`disabled` instead of queued. `MAIL_PORT` defaults to **2525** rather than 587,
because Render blocks the usual submission port outbound.

`/actuator/health` deliberately checks neither the database nor the mail server.
It is the liveness target and the deploy gate, so it must not be able to fail
over something outside this process — a lesson this project learned the
expensive way, twice.

---

## Measured results

Measured against the live deployment on 7 August 2026. Ingestion on Render
(Frankfurt, free), dashboard on Vercel (`fra1`), database on Neon
(`eu-central-1`).

### The clients, against the live collector

| Check | Result |
|---|---|
| 5,000 captures against a collector that does not answer | 98.9 ms total, **19.8 µs per capture**, nothing thrown |
| Queue at capacity during that burst | held at exactly 25, never grew |
| Accounting after 5,000 captures | `accepted = sent + dropped + queued`, exactly |
| **Cold start: capture while the collector is asleep** | **9 failed attempts over 114 s, 0 events lost, all delivered** |
| Backoff between those attempts | 6 s, 6 s, 6 s, 14 s, 18 s, 12 s… growing and jittered |
| Java: exception out of a controller | reported, request still returned the application's own 500 in **57 ms** |
| Java: manual capture | reported, `sent=2 dropped=0 failedAttempts=0` |
| Grouping of what the clients sent | `demo.js#resolveUser` and `CheckoutController$CartService#total`, neither degraded |
| In-app split, Node demo | 4 application frames, 6 `node:internal` frames |
| Shutdown flush with an unreachable collector | bounded, process exited |

The cold-start row is the one the clients exist for. The collector had been idle
for nineteen minutes; the application captured two errors and carried on
immediately, the client spent 114 seconds failing at it, and then delivered both.
Nothing was lost, and nothing about those 114 seconds was visible to the code
that reported the errors.

That 114 seconds is also the top of the range measured in step 0 — the clients
were built against 95 to 114 seconds and met the worst of it.

### One thing this found

The shutdown flush was bounded by its timeout **plus** one request timeout, not
by its timeout: an attempt starting just inside the deadline ran its own full
five seconds past it, so a three-second promise was really eight. It showed up as
a demo run whose "3 s" flush took 5,023 ms.

The fix is that a flush attempt is now given only the time that is left. The
original tests missed it because their fake transport answered instantly; there
are now tests with a slow one, in both clients.

### Detection, on the live deployment

| Check | Result |
|---|---|
| Detectors recorded per bucket | 3 of 3, firing or not |
| Detectors marked active | exactly 1 (`poisson`) |
| A spike over a near-empty baseline | all three fired, scores 16.0 / 12.6 / 7.9 against a threshold of 3 |
| Alerts raised from that | **1** — only the active detector may act |
| Eight events into the same spike | still 1 alert, cooldown held |
| Steady baseline of 50, hour of 60 | only `zscore` fired; no alert, disagreement recorded |
| A new group with 6 errors and no history | scores 12.0 / 8.9 / 6.0, **none fired** — no baseline to deviate from |
| Alert with no mail configured | recorded as `disabled`, not queued |
| Failed delivery | retried, then `failed` with the reason kept |
| Alert with mail configured | born `pending`, delivered on the first attempt |
| Delivery latency, first send of the process | 4.5 s |
| Delivery latency, steady state | **0.92 s** |
| Alerts raised before mail was configured | stayed `disabled`, never sent retroactively |

The two delivery numbers are worth separating. The first send of a process pays
for loading the mail stack and negotiating TLS; every send after it is a plain
SMTP conversation just under a second. Neither is anywhere near the ten-second
timeout, and the distinction matters because a timeout would have surfaced as
`failed` with a reason attached rather than as a slow success.

The last row is a design choice rather than an accident: configuring mail on an
existing deployment does not deliver the backlog, so nobody inherits a mailbox
full of things that already happened.

The sixth row is the one shadow mode exists for. Sixty errors against a flat
history of fifty reads as ten sigma to the z-score, because a perfectly steady
history collapses its denominator onto the floor and the floor then does all the
work; Poisson puts the same hour at roughly one in eleven and says nothing. The
disagreement is recorded and nobody is paged.

The seventh is the guard that stops a detector from being confidently wrong
about a group it has never seen before.

### Volume and time, on the live deployment

| Check | Result |
|---|---|
| Rollup totals vs group counters | identical for every group |
| Backfilled rollups from pre-existing events | 5 groups, totals match |
| Duplicate `event_id` | counted once in the rollup |
| Trend after its events are deleted | unchanged (tested) |
| Sweep on a cold start | ran automatically, 14-day window |
| Sweep after a wake from sleep | **ran again, unprompted** |
| Storage | 7.8 MB of 512 MB |
| Pre-grouping orphan events | cleaned up by the migration |
| Group resolved, then the fault returns | `regressed`, 1.7.0 → 1.8.0 recorded |
| A later event on an already-regressed group | not re-reported as a regression |
| `PATCH /api/groups/{id}` without the key | 401 |
| `PATCH` on a group that does not exist | 404 |

The wake sweep is the one worth pointing at. The service slept for nineteen
minutes, took 104 seconds to come back, and swept without anybody asking — which
is the arm of the design that a scheduler cannot have, because there is nothing
running for a scheduler to fire into.

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
| Dashboard renders groups | 9 groups, with sparklines and storage status |
| **Dashboard while ingestion is asleep** | **200 in 0.40 s, full data** |
| Group list, with 24-hour sparklines per group | 0.40–0.58 s warm |
| Group detail, trend + similarity + frame breakdown | 0.36–0.52 s across all three ranges |
| Alerts and detector scorecard pages | 0.31–0.72 s |
| First request after a fully idle period | 1.8–3.3 s (see below) |
| Neon query time from `fra1` | 6–16 ms |
| Ingestion cold start | **95, 104, 104, 104, 106, 114, 116 s**, measured seven times |
| Ingestion when warm | 0.19–0.26 s |
| `stacklight_web` privileges | `SELECT` succeeds, `INSERT` denied |

### The claim, and the control that backs it

The dashboard measurements above were taken 20 minutes after the last request to
the ingestion service, and they are re-run every time the read path changes.
Step 1 added a lateral join and a trigram search; step 2 added rollup lookups, a
per-group sparkline query and a storage-size query; step 3 added two whole pages.
Each of those is a chance to accidentally introduce a dependency on the service
that is supposed to be optional, so the claim is measured again rather than
inherited.

To confirm the service was genuinely asleep rather than merely idle, the next
request after the measurement was timed. The two are seven seconds apart:

```
22:41:10   dashboard   200 in 0.40 s — 9 groups, charts, alerts, scorecard
22:41:17   ingestion   200 in 104.2 s
```

So during the same window in which the ingestion service could not answer at all,
the dashboard served everything — group list, sparklines, storage status, trend
charts, frame breakdown, fingerprint input, similar groups, alerts and the
detector scorecard — in under half a second.

That is the architectural bet, tested rather than asserted. A static check backs
the measurement: nothing under `web/` imports a HTTP client or names the
ingestion host.

### The read path has its own cold start, and it is not free

The first dashboard request after a long idle period takes noticeably longer than
the rest — 5.6 seconds when the database had been idle for hours, 1.8 seconds
when it had been idle for twenty minutes; every request after it lands under half
a second. That is the Neon compute waking from scale-to-zero, stacked on a cold
Vercel function.

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
