import test from "node:test";
import assert from "node:assert/strict";

import {
  parseFilters,
  encodeCursor,
  decodeCursor,
  toQueryString,
  NO_FILTERS,
} from "../lib/group-filters.ts";

test("absent parameters mean no filter, not empty strings", () => {
  // The query treats null as "no filter" via an is-null guard, so an empty
  // string reaching SQL would filter on the empty string and return nothing.
  assert.deepEqual(parseFilters({}), NO_FILTERS);
  assert.deepEqual(parseFilters({ service: "", status: "", q: "" }), NO_FILTERS);
  assert.deepEqual(parseFilters({ q: "   " }), NO_FILTERS);
});

test("a known status is kept and an invented one is dropped", () => {
  assert.equal(parseFilters({ status: "regressed" }).status, "regressed");
  assert.equal(parseFilters({ status: "OPEN" }).status, "open");
  assert.equal(parseFilters({ status: "banana" }).status, null);
});

test("service and search text are trimmed", () => {
  const filters = parseFilters({ service: "  checkout-api ", q: " null " });

  assert.equal(filters.service, "checkout-api");
  assert.equal(filters.q, "null");
});

test("a repeated parameter takes the first value rather than an array", () => {
  // Next hands over string[] when a key appears twice. Passing that straight
  // into a query would send an array where a text parameter is expected.
  assert.equal(parseFilters({ service: ["a", "b"] }).service, "a");
});

test("an over-long search is cut rather than sent whole", () => {
  const filters = parseFilters({ q: "x".repeat(500) });

  assert.equal(filters.q?.length, 200);
});

test("a cursor survives a round trip", () => {
  const cursor = { lastSeen: "2026-08-10T12:34:56.789012Z", id: 42 };

  assert.deepEqual(decodeCursor(encodeCursor(cursor)), cursor);
});

test("the cursor keeps microseconds, because the sort key needs them", () => {
  // last_seen is rendered to the second everywhere it is displayed. A cursor
  // truncated the same way would sit exactly on a row boundary and either skip
  // or repeat rows whose timestamps share a second.
  const encoded = encodeCursor({ lastSeen: "2026-08-10T12:34:56.789012Z", id: 1 });

  assert.match(encoded, /\.789012Z/);
});

test("a malformed cursor is no cursor, not an error", () => {
  // These arrive from hand-edited URLs and stale bookmarks. Falling back to the
  // first page is the only behaviour that does not show the reader a crash.
  assert.equal(decodeCursor(undefined), null);
  assert.equal(decodeCursor(""), null);
  assert.equal(decodeCursor("nonsense"), null);
  assert.equal(decodeCursor("2026-08-10T12:34:56.789012Z"), null);
  assert.equal(decodeCursor("2026-08-10T12:34:56.789012Z|notanumber"), null);
  assert.equal(decodeCursor("2026-08-10T12:34:56.789012Z|-1"), null);
  assert.equal(decodeCursor("not-a-time|5"), null);
});

test("a query string carries the filters and omits what is unset", () => {
  assert.equal(toQueryString(NO_FILTERS), "");
  assert.equal(
    toQueryString({ service: "checkout-api", status: "open", q: null }),
    "?service=checkout-api&status=open",
  );
});

test("the next-page link keeps the filters alongside the cursor", () => {
  // Losing the filters on page two is the classic version of this bug.
  const query = toQueryString(
    { service: "web-api", status: null, q: "TypeError" },
    "2026-08-10T12:34:56.789012Z|7",
  );

  assert.match(query, /service=web-api/);
  assert.match(query, /q=TypeError/);
  assert.match(query, /after=/);
});
