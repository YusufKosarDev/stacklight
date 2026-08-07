import { neon } from "@neondatabase/serverless";

/**
 * Neon handle for the dashboard read path.
 *
 * Uses the HTTP (SQL-over-fetch) driver rather than a TCP pool on purpose:
 *
 *  - No connection is held open, so the Neon compute can still scale to zero
 *    while the dashboard is idle. A pool would burn CU-hours around the clock.
 *  - No handshake on a cold serverless invocation.
 *
 * Built lazily so `next build` succeeds without DATABASE_URL present.
 */
export function sql() {
  const raw = process.env.DATABASE_URL;

  if (!raw) {
    throw new Error("DATABASE_URL is not set");
  }

  // Strip a leading byte order mark and surrounding whitespace. Neither is ever
  // meaningful in a connection string, but both survive a trip through the
  // tooling that writes environment variables, and a smuggled BOM makes the
  // driver reject an otherwise correct URL.
  const BOM = "\uFEFF";
  const url = (raw.startsWith(BOM) ? raw.slice(BOM.length) : raw).trim();

  return neon(url);
}
