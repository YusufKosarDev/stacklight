# sdk/java

The Java client, and a Spring Boot starter that wires it up.

A Maven reactor with two modules:

| | |
|---|---|
| `stacklight-client` | the client itself — bounded queue, background dispatcher, retry with backoff. No dependencies. |
| `stacklight-spring` | auto-configuration, a handler-exception resolver and an uncaught-exception handler, so an application reports without calling anything. |

```bash
./mvnw verify
```

**Built against Java 17, not the collector's 21.** This runs on somebody else's
classpath and cannot make their choice for them.

**Shaped by a collector that sleeps.** Every default here was picked against a
measured cold start of 95–116 seconds, one of which returned 503 rather than
waiting. So `captureException` enqueues and returns — it opens no connection,
waits for none and throws nothing, because the caller is usually already dealing
with something that went wrong. The queue is bounded and sheds its oldest entry
when full; the request timeout is short and the backoff ceiling deliberately sits
under the wake time, so several attempts span a cold start instead of one long
wait sitting past it.

**Not published to any registry.** The example under
[`examples/java-demo`](../../examples/java-demo) builds against it from source,
and CI builds that example on every push so the starter cannot rot into something
that no longer compiles against what it is meant to demonstrate.

The measurements behind those defaults are in the
[main README](../../README.md#the-clients).
