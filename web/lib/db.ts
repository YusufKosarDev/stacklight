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
  const url = process.env.DATABASE_URL;

  if (!url) {
    throw new Error("DATABASE_URL is not set");
  }

  return neon(url);
}
