import test, { mock } from "node:test";
import assert from "node:assert/strict";

import { bytesParts, relativeTime } from "../lib/format.ts";

test("bytes stay in bytes below a kilobyte", () => {
  assert.deepEqual(bytesParts(0), { value: "0", unit: "B" });
  assert.deepEqual(bytesParts(1023), { value: "1023", unit: "B" });
});

test("the unit changes exactly at each boundary", () => {
  assert.equal(bytesParts(1024).unit, "KB");
  assert.equal(bytesParts(1024 * 1024 - 1).unit, "KB");
  assert.equal(bytesParts(1024 * 1024).unit, "MB");
});

test("kilobytes are whole and megabytes carry one decimal", () => {
  // The storage tile sets the figure and the unit at different sizes, so the
  // split has to survive: "8.1" and "MB", never "8.1 MB" as one string.
  assert.deepEqual(bytesParts(1536), { value: "2", unit: "KB" });
  assert.deepEqual(bytesParts(8.5 * 1024 * 1024), { value: "8.5", unit: "MB" });
});

/** The timestamp shape every query in lib/queries.ts produces via to_char. */
function agoBySeconds(seconds: number): string {
  const then = new Date(Date.now() - seconds * 1000);
  return then.toISOString().replace("T", " ").slice(0, 19);
}

test("relative time picks the coarsest unit that fits", () => {
  assert.match(relativeTime(agoBySeconds(5)), /^\d+s ago$/);
  assert.match(relativeTime(agoBySeconds(120)), /^2m ago$/);
  assert.match(relativeTime(agoBySeconds(3 * 3600)), /^3h ago$/);
  assert.match(relativeTime(agoBySeconds(2 * 86400)), /^2d ago$/);
});

test("the timestamp is read as UTC, not as local time", () => {
  // The queries hand over "YYYY-MM-DD HH:MM:SS" with no zone marker. Without
  // the Z that relativeTime appends, this machine's offset would be applied
  // and every age would be wrong by hours -- invisibly, and only off this
  // developer's machine.
  const oneHourAgo = agoBySeconds(3600);
  assert.equal(relativeTime(oneHourAgo), "1h ago");
});

test("the unit boundaries fall where they are supposed to", () => {
  /*
   * On a frozen clock, because this is the one test that cannot use the real
   * one. relativeTime rounds, so a timestamp built 59 seconds in the past and
   * read a fraction of a second later reads as 60 and the assertion flips --
   * which it did, intermittently, before the clock was pinned. Boundaries are
   * exactly where the bugs are, so the answer is to control the clock rather
   * than to test somewhere safer.
   */
  const now = Date.UTC(2026, 7, 10, 12, 0, 0);
  mock.timers.enable({ apis: ["Date"], now });

  try {
    const at = (secondsAgo: number) =>
      new Date(now - secondsAgo * 1000)
        .toISOString()
        .replace("T", " ")
        .slice(0, 19);

    assert.equal(relativeTime(at(59)), "59s ago");
    assert.equal(relativeTime(at(60)), "1m ago");
    assert.equal(relativeTime(at(3599)), "60m ago");
    assert.equal(relativeTime(at(3600)), "1h ago");
    assert.equal(relativeTime(at(86399)), "24h ago");
    assert.equal(relativeTime(at(86400)), "1d ago");
  } finally {
    mock.timers.reset();
  }
});
