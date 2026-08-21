# sdk/node

The Node client. CommonJS, **no dependencies and no lockfile** — a property the
main README claims and CI enforces.

```js
const { StacklightClient } = require("@stacklight/client");

const stacklight = StacklightClient.start({
  endpoint: "https://collector.example.com/api/events",
  apiKey: process.env.STACKLIGHT_KEY,
  service: "checkout-api",
  release: "2026.8.1",
});

stacklight.captureException(error);
```

```bash
npm test               # the runtime's own test runner; nothing to install
```

`captureException` enqueues and returns. It opens no connection, waits for none
and throws nothing: the caller is usually already dealing with something that
went wrong, and making that worse is not an option available to an error
reporter. `null` and `undefined` are ignored rather than reported as faults of
their own.

Started without an endpoint and key the client is **inert rather than absent** —
capture becomes a no-op and nothing throws, so an application can run somewhere
with no collector without that being a case it has to handle.

Types ship in [`src/index.d.ts`](src/index.d.ts), hand-written for the same
reason there are no dependencies.

**One thing worth knowing before adding a process handler.** Registering an
`uncaughtException` listener suppresses Node's default behaviour of printing the
error and exiting, so a reporter that merely listened would silently turn a crash
into a hang. This one restores that behaviour when nothing else has claimed it,
and leaves the application in charge when it has its own listener.

The defaults were chosen against a measured collector rather than picked round;
[the main README](../../README.md#the-clients) has the numbers.
