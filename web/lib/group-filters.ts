import type { GroupStatus, Range } from "./queries.ts";

/**
 * The group list's URL state: what is being filtered, how far back it looks, and
 * where the page starts.
 *
 * Kept apart from the queries so it can be tested without a database, and so
 * there is one place that decides what an absent, repeated or malformed
 * parameter means. All of those arrive routinely from hand-edited URLs, stale
 * bookmarks and links that were built before a parameter existed.
 */

export type GroupFilters = {
  service: string | null;
  status: GroupStatus | null;
  q: string | null;
};

export const NO_FILTERS: GroupFilters = { service: null, status: null, q: null };

const STATUSES: GroupStatus[] = ["open", "resolved", "ignored", "regressed"];

/**
 * How far back the overview's trend looks.
 *
 * A subset of the group page's `Range` rather than a type of its own, so the two
 * cannot drift into meaning different things by the same name. `24h` is
 * deliberately absent: the overview draws one bar per day, and an hourly window
 * on a daily chart would be one bar. The group page, which switches its bucket
 * width with the range, still offers all three.
 */
export type OverviewRange = Extract<Range, "7d" | "30d">;

export const OVERVIEW_RANGES: { key: OverviewRange; label: string }[] = [
  { key: "7d", label: "7 days" },
  { key: "30d", label: "30 days" },
];

/** The window a link should use when nothing in the URL says otherwise. */
export const DEFAULT_RANGE: OverviewRange = "7d";

/** Long enough for any real search, short enough not to be a payload. */
const MAX_QUERY = 200;

type RawParams = Record<string, string | string[] | undefined>;

/**
 * Next hands over `string[]` when a key appears twice in the URL. Taking the
 * first value keeps a duplicated parameter from reaching a query that expects
 * text.
 */
function first(value: string | string[] | undefined): string | null {
  const raw = Array.isArray(value) ? value[0] : value;
  if (raw === undefined) return null;
  const trimmed = raw.trim();
  return trimmed === "" ? null : trimmed;
}

export function parseFilters(params: RawParams): GroupFilters {
  const status = first(params.status)?.toLowerCase() ?? null;
  const q = first(params.q);

  return {
    service: first(params.service),
    // An unrecognised status becomes no filter rather than a filter matching
    // nothing, so a typo shows everything instead of an empty page.
    status: STATUSES.includes(status as GroupStatus)
      ? (status as GroupStatus)
      : null,
    q: q === null ? null : q.slice(0, MAX_QUERY),
  };
}

/**
 * The window the URL asked for, or null when it did not ask for a usable one.
 *
 * Null is a third state rather than a spelling of the default, and the overview
 * relies on the difference: absent means *choose a window for me*, where `7d`
 * means seven days and to leave it at that even if seven days are empty. A page
 * that widened the window under somebody who had just clicked "7 days" would
 * have a button that appears not to work.
 *
 * An unrecognised value is treated as absent, for the same reason an
 * unrecognised status becomes no filter: these arrive from hand-edited URLs and
 * stale bookmarks, and showing the usual thing beats showing nothing.
 */
export function parseRange(params: RawParams): OverviewRange | null {
  const raw = first(params.range)?.toLowerCase() ?? null;
  return OVERVIEW_RANGES.some((option) => option.key === raw)
    ? (raw as OverviewRange)
    : null;
}

export type Cursor = { lastSeen: string; id: number };

/**
 * Keyset position: the sort key of the last row already shown.
 *
 * Microseconds are kept deliberately. `last_seen` is rendered to the second
 * everywhere it is displayed, and a cursor truncated the same way would sit on
 * a row boundary and skip or repeat every group sharing that second.
 */
export function encodeCursor(cursor: Cursor): string {
  return `${cursor.lastSeen}|${cursor.id}`;
}

const TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/;

export function decodeCursor(raw: string | string[] | undefined): Cursor | null {
  const value = first(raw);
  if (value === null) return null;

  const parts = value.split("|");
  if (parts.length !== 2) return null;

  const [lastSeen, rawId] = parts;
  if (!TIMESTAMP.test(lastSeen)) return null;

  const id = Number(rawId);
  if (!Number.isInteger(id) || id <= 0) return null;

  return { lastSeen, id };
}

/**
 * The querystring for a link that keeps the current view.
 *
 * Every link on the page goes through this. Dropping the filters when paging is
 * the classic version of this bug, and it is only avoided by having one place
 * that builds the URL. The range joins them here for the same reason: it is one
 * more thing a link can silently reset, and the chip that clears a filter has no
 * business also throwing away the window somebody chose.
 *
 * A range handed in is always written out, including the default one. It used to
 * be omitted to keep the plain list on a plain URL, and that stopped being safe
 * when an absent range came to mean *choose for me*: the "7 days" button would
 * have linked to `/`, the page would have chosen again, and the button would have
 * looked broken. Every link that knows which window it wants now says so, and the
 * only URL without one is the one nobody generated.
 */
export function toQueryString(
  filters: GroupFilters,
  options: { after?: string; range?: OverviewRange } = {},
): string {
  const params = new URLSearchParams();
  if (filters.service) params.set("service", filters.service);
  if (filters.status) params.set("status", filters.status);
  if (filters.q) params.set("q", filters.q);
  if (options.range) params.set("range", options.range);
  if (options.after) params.set("after", options.after);

  const query = params.toString();
  return query === "" ? "" : `?${query}`;
}
