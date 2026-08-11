/**
 * Sends one hour of the scenario, then exits.
 *
 * Run once an hour by a workflow. It holds no state: which hour of the schedule this is
 * comes from the wall clock, so a missed run leaves a gap rather than a shifted schedule,
 * and the run stops itself once the schedule is over.
 *
 * It reports through the project's own Node client rather than posting JSON directly.
 * That is deliberate: the client is the piece built around a collector that sleeps, so
 * every hour this runs is another test of the bounded queue, the backoff and the
 * cold-start retry against the real thing. Posting directly would be easier and would
 * measure nothing.
 *
 *   COLLECTOR_URL=... INGEST_API_KEY=... node tools/traffic/generate.mjs
 *   node tools/traffic/generate.mjs --hour 7 --dry-run
 */
import sdk from "../../sdk/node/src/index.js";
import { hourIndex, plan, HOURS, START_ISO } from "./scenario.mjs";

const { StacklightClient } = sdk;

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const forcedHour = args.includes("--hour") ? Number(args[args.indexOf("--hour") + 1]) : null;

/**
 * Long enough to outlast a cold start.
 *
 * The client's default is three seconds, which is right for a process shutting down and
 * wrong here: the first attempt of the hour usually lands on a sleeping collector, and
 * the wake was measured at 95 to 116 seconds. A flush that gave up at three would report
 * a queue full of events it had simply not waited for.
 */
const FLUSH_TIMEOUT_MS = 240_000;

/** Values the normalizer is supposed to strip, so that a group does not fragment on them. */
const uuid = () => crypto.randomUUID();
const id = () => Math.floor(1000 + Math.random() * 9000);

const TRACES = {
  "checkout-api": (n) => ({
    message: `could not reserve stock for cart ${uuid()}`,
    stack: [
      `java.lang.IllegalStateException: could not reserve stock for cart ${uuid()}`,
      "\tat com.example.checkout.ReservationService.reserve(ReservationService.java:88)",
      "\tat com.example.checkout.CheckoutController.submit(CheckoutController.java:54)",
      "\tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)",
      "\tat org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)",
      "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)",
    ].join("\n"),
  }),
  "search-indexer": () => ({
    message: `Cannot read properties of undefined (reading 'documentId') for job ${id()}`,
    stack: [
      "TypeError: Cannot read properties of undefined (reading 'documentId')",
      "    at buildIndex (/app/src/indexer/build.js:142:31)",
      "    at processQueue (/app/src/indexer/queue.js:88:9)",
      "    at async Worker.run (/app/src/worker.js:37:5)",
      "    at async node:internal/process/task_queues:95:5",
    ].join("\n"),
  }),
  "media-transcoder": () => ({
    message: `Invalid array length while decoding segment ${id()}`,
    stack: [
      "RangeError: Invalid array length",
      "    at allocateFrameBuffer (/app/src/transcode/buffer.js:61:17)",
      "    at decodeSegment (/app/src/transcode/decode.js:203:22)",
      "    at /app/node_modules/p-limit/index.js:57:20",
      "    at async node:internal/process/task_queues:95:5",
    ].join("\n"),
  }),
  "notification-worker": () => ({
    message: `Cannot invoke "String.length()" because "template" is null for user ${uuid()}`,
    stack: [
      'java.lang.NullPointerException: Cannot invoke "String.length()" because "template" is null',
      "\tat com.example.notify.TemplateRenderer.render(TemplateRenderer.java:47)",
      "\tat com.example.notify.DispatchWorker.send(DispatchWorker.java:112)",
      "\tat java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)",
    ].join("\n"),
  }),
  "payments-api": () => ({
    message: `connection pool exhausted acquiring connection for payment ${uuid()}`,
    stack: [
      "org.springframework.dao.DataAccessResourceFailureException: connection pool exhausted",
      "\tat com.example.payments.LedgerRepository.record(LedgerRepository.java:73)",
      "\tat com.example.payments.PaymentService.settle(PaymentService.java:129)",
      "\tat org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119)",
    ].join("\n"),
  }),
  "session-store": () => ({
    message: `session ${uuid()} evicted before write completed`,
    stack: [
      "Error: session evicted before write completed",
      "    at flushSession (/app/src/session/flush.js:94:13)",
      "    at Timeout._onTimeout (/app/src/session/reaper.js:28:7)",
      "    at listOnTimeout (node:internal/timers:594:17)",
    ].join("\n"),
  }),
};

/** An Error carrying a trace the runtime did not produce, so the collector parses it as real. */
function faultFor(service) {
  const { message, stack } = TRACES[service.name]();
  const error = new Error(message);
  error.name = service.exceptionType;
  error.stack = stack;
  return error;
}

/**
 * Keeps the event loop alive while the client is waiting.
 *
 * The client unrefs the timer it backs off on, which is correct of it: a reporter must
 * never be the reason a host application stays up. This process has no host application,
 * so during a backoff there is nothing referenced left, the loop drains, and Node exits
 * with code 13 for an unsettled top-level await -- ten seconds into a wake that takes a
 * hundred. The fault is this file's for having nothing else to do, not the client's.
 */
function holdTheLoopOpen() {
  const timer = setInterval(() => {}, 60_000);
  return () => clearInterval(timer);
}

async function main() {
  const hour = forcedHour !== null ? forcedHour : hourIndex();
  const work = plan(hour);

  if (hour === null) {
    console.log(`schedule is not running (start ${START_ISO}, ${HOURS} hours); nothing sent`);
    return 0;
  }
  if (work.length === 0) {
    console.log(`hour ${hour}: every service is quiet this hour; nothing sent`);
    return 0;
  }

  const total = work.reduce((sum, w) => sum + w.count, 0);
  console.log(`hour ${hour} of ${HOURS}: ${total} events across ${work.length} services`);
  for (const { service, count } of work) {
    console.log(`  ${service.name.padEnd(22)} ${String(count).padStart(3)}  (${service.aims})`);
  }

  if (dryRun) {
    console.log("dry run; nothing sent");
    return 0;
  }

  const endpoint = process.env.COLLECTOR_URL;
  const apiKey = process.env.INGEST_API_KEY;
  if (!endpoint || !apiKey) {
    console.log("COLLECTOR_URL or INGEST_API_KEY is not set; nothing sent");
    return 0;
  }

  let failed = false;
  const release = holdTheLoopOpen();
  try {
    failed = await send(work, endpoint, apiKey);
  } finally {
    release();
  }
  return failed ? 1 : 0;
}

async function send(work, endpoint, apiKey) {
  let failed = false;

  for (const { service, count } of work) {
    const client = StacklightClient.start({
      endpoint: `${endpoint.replace(/\/$/, "")}/api/events`,
      apiKey,
      service: service.name,
      platform: service.platform,
      release: "1.9.0",
      // Room for the largest hour with margin, so a drop means a real fault rather than
      // a queue sized too tightly to tell.
      queueCapacity: 256,
      shutdownTimeoutMs: FLUSH_TIMEOUT_MS,
      // This process is a generator, not an application. Its own crashes are not events
      // about the services it is pretending to be.
      captureUncaught: false,
      captureUnhandledRejections: false,
    });

    for (let i = 0; i < count; i++) {
      client.captureException(faultFor(service));
    }

    await client.flush();
    const stats = client.stats();
    await client.close();

    const ok = stats.sent === count && stats.dropped === 0;
    console.log(
      `  ${service.name.padEnd(22)} sent=${stats.sent}/${count} dropped=${stats.dropped} ` +
        `queued=${stats.queued} failedAttempts=${stats.failedAttempts}` +
        (stats.lastError ? ` lastError=${stats.lastError}` : "") +
        (ok ? "" : "  <-- incomplete"),
    );
    if (!ok) failed = true;
  }

  return failed;
}

process.exitCode = await main();
