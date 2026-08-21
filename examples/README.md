# examples

Two applications that report to a real collector. Nothing here is mocked — the
point of these is to be the thing that finds out whether the clients work against
a service that goes to sleep.

| | |
|---|---|
| `java-demo/` | A Spring Boot application with the starter on its classpath and a controller that throws on purpose. It reports without calling the client directly, which is what the starter is for. |
| `node-demo/` | `demo.js` sends a real error. `overflow.js` points at a discard port where nothing listens, so the bounded queue and the drop counter can be watched doing their job. |

```bash
# Java
cd sdk/java && ./mvnw -DskipTests install
cd ../../examples/java-demo && ./mvnw package

# Node
STACKLIGHT_ENDPOINT=https://your-collector/api/events \
STACKLIGHT_KEY=... node examples/node-demo/demo.js
```

`java-demo` is built in CI against the freshly installed starter on every push,
so it cannot quietly stop compiling against the thing it demonstrates. The Node
files are parsed by `node --check` for the same reason at less cost.

Running them, and what to expect the first attempt against a sleeping collector
to do, is in the [main README](../README.md#running-the-examples).
