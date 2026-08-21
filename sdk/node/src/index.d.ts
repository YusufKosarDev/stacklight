/**
 * Type declarations for `@stacklight/client`.
 *
 * `package.json` has pointed `types` at this file since the package was written, and
 * until now the file was not here: a TypeScript caller got an implicit `any` for the
 * whole client, or an error under `noImplicitAny`, and neither said why.
 *
 * Hand-written rather than generated. The client is plain CommonJS with no build step --
 * that is the point of it, and a compiler brought in to emit this would be the first
 * dependency in a package whose README claims none. The cost is that these declarations
 * are only as honest as the person editing them, so anything added to `index.js` has to
 * be added here in the same commit.
 */

/**
 * Severity, as the collector stores it.
 *
 * The dashboard styles `ERROR`, `WARN` and `INFO`; the collector accepts any non-blank
 * string up to twenty characters, so the union is open rather than closed.
 */
export type StacklightLevel = "ERROR" | "WARN" | "INFO" | (string & {});

/**
 * What the client sends for one fault.
 *
 * Built from the caught value rather than supplied by the caller -- there is no public
 * method that takes one of these -- and named here because a transport receives them.
 */
export interface StacklightEvent {
  eventId: string;
  service: string;
  level: StacklightLevel;
  message: string;
  platform: string;
  exceptionType: string;
  /** Absent when the captured value carried no stack. */
  stacktrace?: string;
  /** Absent unless `release` was configured. */
  release?: string;
}

export interface StacklightOptions {
  /** Full URL of the ingest endpoint. Empty means the client is inert. */
  endpoint: string;
  /** Empty means the client is inert. */
  apiKey: string;
  /** Defaults to `"unknown"`. */
  service: string;
  release?: string;
  /** Defaults to `"javascript"`. */
  platform: string;

  /** Bounded on purpose: at capacity the oldest event gives way. Default 512. */
  queueCapacity: number;
  /** Events per request. Default 20, which is the collector's batch endpoint. */
  batchSize: number;

  /** Default 5000. Short by design against a collector that may be asleep. */
  requestTimeoutMs: number;
  /** Default 1000. */
  retryBaseDelayMs: number;
  /** Default 30000. */
  retryMaxDelayMs: number;
  /** Default 3000. The budget a flush on shutdown is allowed. */
  shutdownTimeoutMs: number;

  /** Default true. */
  captureUncaught: boolean;
  /** Default true. */
  captureUnhandledRejections: boolean;
  /** Default false. Writes delivery detail to stderr. */
  debug: boolean;
}

export interface StacklightStats {
  /** Events the queue took, whether or not they have been sent. */
  accepted: number;
  sent: number;
  /** Shed by a full queue, plus those the collector refused outright. */
  dropped: number;
  /** Waiting to be sent. */
  queued: number;
  failedAttempts: number;
  /** The last delivery failure, or null if there has not been one. */
  lastError: string | null;
}

/** One event the collector refused for a reason retrying cannot change. */
export interface DiscardedEvent {
  event: StacklightEvent;
  reason: string;
}

/**
 * What a delivery attempt reports back.
 *
 * `pending` is what the dispatcher must requeue: an attempt that failed owes the whole
 * batch back, and a partial success owes the part the collector did not take.
 */
export interface TransportResult {
  delivered: boolean;
  /** Whether another attempt could succeed. A bad key or a malformed body cannot. */
  retryable: boolean;
  detail: string | null;
  accepted: number;
  discarded: DiscardedEvent[];
  pending: StacklightEvent[];
}

/**
 * The delivery seam.
 *
 * Injectable so a test can watch what would have been sent without a collector; the
 * client builds an HTTP one when none is given.
 */
export interface Transport {
  send(
    batch: StacklightEvent[],
    timeoutMs?: number,
  ): Promise<TransportResult>;
}

export declare class StacklightClient {
  constructor(options: StacklightOptions, transport?: Transport);

  /**
   * Starts a client.
   *
   * Without an endpoint and key it is inert rather than absent: capture becomes a no-op
   * and nothing throws, so an application can run somewhere with no collector without
   * that being a case it has to handle.
   */
  static start(
    input?: Partial<StacklightOptions>,
    transport?: Transport,
  ): StacklightClient;

  readonly options: StacklightOptions;
  /** False when no endpoint and key were configured. */
  readonly enabled: boolean;

  /**
   * Enqueues and returns. Opens no connection, waits for none and throws nothing: the
   * caller is usually already dealing with something that went wrong.
   *
   * `null` and `undefined` are ignored rather than reported as faults of their own.
   */
  captureException(error: unknown, level?: StacklightLevel): void;

  /** Blank messages are ignored. */
  captureMessage(message: unknown, level?: StacklightLevel): void;

  /** Sends what is queued, within `shutdownTimeoutMs`. */
  flush(): Promise<void>;

  stats(): StacklightStats;

  /** Stops the dispatcher, removes the process handlers and flushes what is left. */
  close(): Promise<void>;

  installProcessHandlers(): void;
  removeProcessHandlers(): void;
}
