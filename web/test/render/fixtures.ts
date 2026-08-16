/**
 * Stands in for `@/lib/queries` while a page is rendered.
 *
 * Every page reaches the database through that module and through nothing else, so this
 * is the only seam a render test needs. It is the seam the pages already have rather
 * than one cut for testing.
 *
 * ## The types are the point
 *
 * Each function below is annotated with the real exported signature, imported for its
 * types only so nothing from the driver is pulled in at run time. That makes the
 * compiler the thing keeping these fixtures honest: change what a query returns and this
 * file stops compiling, rather than the tests carrying on green against data shaped like
 * last month's schema. Fixtures that can drift are worse than no fixtures, because they
 * keep passing.
 *
 * ## How a test uses it
 *
 * `scenario` is mutable and `reset()` puts it back. A test sets the rows it wants,
 * renders the page, and asserts on the HTML. The page's own shaping -- the degraded
 * reasons, the frame split, the empty branches -- runs for real; only the rows are
 * arranged.
 */
import type {
  Alert,
  Bucket,
  DetectorRow,
  GroupDetail,
  GroupPage,
  GroupSummary,
  NavCounts,
  SimilarGroup,
  StorageStatus,
} from "../../lib/queries";

export type {
  Alert,
  Bucket,
  DetectorRow,
  GroupDetail,
  GroupPage,
  GroupStatus,
  GroupSummary,
  NavCounts,
  Range,
  SimilarGroup,
  StorageStatus,
  StoredFrame,
} from "../../lib/queries";

type Scenario = {
  groups: GroupSummary[];
  nextCursor: string | null;
  counts: Record<string, number>;
  trend: { daily: number[]; total: number };
  services: string[];
  sparklines: Map<number, number[]>;
  storage: StorageStatus;
  detail: GroupDetail | null;
  series: Bucket[];
  similar: SimilarGroup[];
  alerts: Alert[];
  detectors: DetectorRow[];
  navCounts: NavCounts;
};

/** A plausible group, so a test only has to state the part it is about. */
export function aGroup(over: Partial<GroupSummary> = {}): GroupSummary {
  return {
    id: 1,
    title: 'NullPointerException: Cannot invoke "String.length()" because "promoCode" is null',
    service: "checkout-api",
    platform: "java",
    level: "error",
    status: "open",
    culprit: "com.example.checkout.CartService#total",
    degraded_reason: null,
    event_count: 14,
    first_seen: "2026-08-11 16:14:12",
    last_seen: "2026-08-13 07:23:33",
    ...over,
  };
}

export function aDetail(over: Partial<GroupDetail> = {}): GroupDetail {
  return {
    ...aGroup(),
    fingerprint: "b2b9c15ea9557bba93353505c471e919",
    fingerprint_version: 1,
    fingerprint_input:
      "java|java.lang.NullPointerException|com.example.checkout.CartService#total|com.example.checkout.CheckoutController#submit",
    exception_type: "java.lang.NullPointerException",
    frames: [
      {
        module: null,
        declaringClass: "com.example.checkout.CartService",
        function: "total",
        file: "CartService.java",
        line: 88,
        inApp: true,
      },
      {
        module: null,
        declaringClass: "com.example.checkout.CheckoutController",
        function: "submit",
        file: "CheckoutController.java",
        line: 54,
        inApp: true,
      },
      {
        module: null,
        declaringClass: "org.springframework.web.servlet.DispatcherServlet",
        function: "doDispatch",
        file: "DispatcherServlet.java",
        line: 1089,
        inApp: false,
      },
    ],
    sampled_count: 0,
    release_first: "1.9.0",
    release_last: "1.9.1",
    resolved_at: null,
    resolved_in_release: null,
    regressed_at: null,
    regressed_in_release: null,
    sample_message: 'Cannot invoke "String.length()" because "promoCode" is null',
    sample_stacktrace:
      'java.lang.NullPointerException: Cannot invoke "String.length()"\n\tat com.example.checkout.CartService.total(CartService.java:88)',
    sample_received_at: "2026-08-13 07:23:33",
    ...over,
  };
}

export function aDetector(over: Partial<DetectorRow> = {}): DetectorRow {
  return {
    detector: "ewma",
    is_active: true,
    fired: 13,
    judged: 111,
    pending: 0,
    tp: 9,
    fp: 4,
    fn: 2,
    tn: 96,
    ...over,
  };
}

export function anAlert(over: Partial<Alert> = {}): Alert {
  return {
    id: 1,
    group_id: 1,
    kind: "spike",
    detector: "ewma",
    observed: 40,
    baseline: 6,
    score: 6.7,
    title: "IllegalStateException: could not reserve stock",
    service: "checkout-api",
    created_at: "2026-08-13 07:23:31",
    delivery_state: "sent",
    delivery_attempts: 1,
    last_error: null,
    ...over,
  };
}

const EMPTY_STORAGE: StorageStatus = {
  events_bytes: 139_264,
  rollups_bytes: 16_384,
  total_bytes: 10_485_760,
  event_rows: 2163,
  rollup_rows: 190,
  oldest_event: "2026-08-11 15:12:00",
  last_sweep_at: "2026-08-13 06:07:00",
  last_sweep_source: "scheduled",
  last_sweep_window_days: 14,
  last_sweep_deleted: 0,
};

function blank(): Scenario {
  return {
    groups: [],
    nextCursor: null,
    counts: {},
    trend: { daily: new Array(7).fill(0), total: 0 },
    services: [],
    sparklines: new Map(),
    storage: EMPTY_STORAGE,
    detail: null,
    series: [],
    similar: [],
    alerts: [],
    detectors: [],
    navCounts: { open_groups: 0, alerts: 0 },
  };
}

export const scenario: Scenario = blank();

/** Back to nothing, so one test cannot leave data lying around for the next. */
export function reset(): void {
  Object.assign(scenario, blank());
}

// --- the surface the pages import -------------------------------------------------

export async function listGroups(): Promise<GroupPage> {
  return { groups: scenario.groups, nextCursor: scenario.nextCursor };
}

export async function countsByStatus(): Promise<Record<string, number>> {
  return scenario.counts;
}

export async function getOverviewTrend(): Promise<{ daily: number[]; total: number }> {
  return scenario.trend;
}

export async function listServices(): Promise<string[]> {
  return scenario.services;
}

export async function listSparklines(): Promise<Map<number, number[]>> {
  return scenario.sparklines;
}

export async function getStorageStatus(): Promise<StorageStatus> {
  return scenario.storage;
}

export async function getGroup(): Promise<GroupDetail | null> {
  return scenario.detail;
}

export async function getGroupSeries(): Promise<Bucket[]> {
  return scenario.series;
}

export async function findSimilarGroups(): Promise<SimilarGroup[]> {
  return scenario.similar;
}

export async function listAlerts(): Promise<Alert[]> {
  return scenario.alerts;
}

export async function getDetectorScorecard(): Promise<DetectorRow[]> {
  return scenario.detectors;
}

export async function getNavCounts(): Promise<NavCounts> {
  return scenario.navCounts;
}
