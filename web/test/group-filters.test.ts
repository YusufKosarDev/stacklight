import test from "node:test";
import assert from "node:assert/strict";

import {
  parseFilters,
  parseRange,
  encodeCursor,
  decodeCursor,
  toQueryString,
  NO_FILTERS,
  DEFAULT_RANGE,
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
    { after: "2026-08-10T12:34:56.789012Z|7" },
  );

  assert.match(query, /service=web-api/);
  assert.match(query, /q=TypeError/);
  assert.match(query, /after=/);
});

test("an absent or invented range is nobody's choice, not the default", () => {
  // Null rather than the default, because the overview treats the two
  // differently: nothing in the URL lets it widen an empty window, where `7d`
  // means seven days and to leave them empty if that is what they are.
  assert.equal(parseRange({}), null);
  assert.equal(parseRange({ range: "" }), null);
  assert.equal(parseRange({ range: "all-time" }), null);
  // `24h` is a range the group page offers and this one does not, so it is not
  // simply an unknown word -- it is a valid range in the wrong place.
  assert.equal(parseRange({ range: "24h" }), null);

  assert.equal(parseRange({ range: "7d" }), "7d");
  assert.equal(parseRange({ range: "30d" }), "30d");
  assert.equal(parseRange({ range: "30D" }), "30d");
  // Next hands over an array when a key appears twice.
  assert.equal(parseRange({ range: ["30d", "7d"] }), "30d");
});

test("a link always spells out the range it is asking for", () => {
  // Including the default. Omitting it would make the "7 days" button link to
  // `/`, which is the one URL that means "choose for me" -- so the page would
  // choose again, and the button would look broken.
  assert.equal(toQueryString(NO_FILTERS, { range: DEFAULT_RANGE }), "?range=7d");
  assert.equal(toQueryString(NO_FILTERS, { range: "30d" }), "?range=30d");
  // A link built without one still asks for nothing, which is how the page keeps
  // a plain `/` meaning what it means.
  assert.equal(toQueryString(NO_FILTERS), "");
});

test("the range survives alongside the filters and the cursor", () => {
  const query = toQueryString(
    { service: "web-api", status: "open", q: null },
    { range: "30d", after: "2026-08-10T12:34:56.789012Z|7" },
  );

  assert.match(query, /service=web-api/);
  assert.match(query, /status=open/);
  assert.match(query, /range=30d/);
  assert.match(query, /after=/);
});
