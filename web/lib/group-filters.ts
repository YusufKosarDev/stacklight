import type { GroupStatus } from "./queries.ts";

/**
 * The group list's URL state: what is being filtered, and where the page starts.
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
 * The querystring for a link that keeps the current filters.
 *
 * Every link on the page goes through this. Dropping the filters when paging is
 * the classic version of this bug, and it is only avoided by having one place
 * that builds the URL.
 */
export function toQueryString(filters: GroupFilters, after?: string): string {
  const params = new URLSearchParams();
  if (filters.service) params.set("service", filters.service);
  if (filters.status) params.set("status", filters.status);
  if (filters.q) params.set("q", filters.q);
  if (after) params.set("after", after);

  const query = params.toString();
  return query === "" ? "" : `?${query}`;
}
