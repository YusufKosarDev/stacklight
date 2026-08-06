# Stacklight

Error ingestion and triage for small services.

**Status: step 0.** This commit proves the deployment pipeline end to end and
nothing more. There is no fingerprinting, grouping, deduplication, time series
or alerting yet — those land in later steps, on top of a chain that is already
known to work in production.

---

## The one thing step 0 proves

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

Step 0 exists to measure that claim rather than assert it.

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
web/       Next.js 16 App Router, deployed to Vercel
.githooks/ commit-msg policy, enabled with core.hooksPath
.github/   CI: policy scan, backend tests, image build, web build
```

## API

`POST /api/events` — requires header `X-Stacklight-Key`.

```json
{
  "eventId": "3f1c9d2e-...",
  "service": "checkout-api",
  "level": "ERROR",
  "message": "NullPointerException in CartService.total",
  "payload": { "release": "1.4.0", "stacktrace": ["..."] }
}
```

`eventId` is optional; the server generates one when it is missing. A repeated
`eventId` is discarded by a unique constraint rather than surfacing as an error,
and the response reports which happened:

```json
{ "eventId": "3f1c9d2e-...", "stored": true }
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

Filled in once both services are live.

| Check | Result |
|---|---|
| `POST /api/events` writes a row | _pending_ |
| Dashboard renders that row | _pending_ |
| **Dashboard while ingestion is asleep** | _pending_ |
| Dashboard response time | _pending_ |
| Ingestion cold start | _pending_ |
