# backend

The ingestion service. Spring Boot 4.1 on Java 21, deployed to Render's free
tier from the `Dockerfile` here.

Everything that happens to an event happens on the way in: it is parsed,
fingerprinted into a group, counted into an hourly rollup, checked against three
anomaly detectors, and — every few hundred events — used as the trigger for a
retention sweep. There is no scheduler, and that is the point: a timer cannot
fire into a process that has fallen asleep.

```bash
./mvnw verify          # needs a running Docker daemon
```

The tests start a real PostgreSQL 17 through Testcontainers and run the actual
Flyway migration, so schema and SQL problems surface here rather than on Neon.
One suite is excluded from `mvn verify` by its tag — see
[`src/test/java/dev/stacklight/backend/scale`](src/test/java/dev/stacklight/backend/scale).

| | |
|---|---|
| `grouping/` | platform parsers, message normalizer, two fingerprinter versions, the replay report |
| `ingest/` | the endpoints, the two-key guard, event / group / rollup persistence, the sweep |
| `detection/` | three detectors, shadow recording, self-scoring, the silence check |
| `alerting/` | the outbox, cooldowns, best-effort mail delivery |
| `retention/` | the sweep, the adaptive window, the startup catch-up |
| `observability/` | correlation id, the meters, the cardinality ceiling |
| `resources/static/` | the triage console this service serves itself |

`src/main/resources/application.yaml` carries the reasoning for every setting
that has one, which is most of them.

The endpoints, the two API keys and why they are two, and the argument the whole
design rests on are in the [main README](../README.md).
