import { getNavCounts, type NavCounts } from "@/lib/queries";

/**
 * The sidebar's badge numbers, fetched behind a catch so they can fail alone.
 *
 * The nav has to render on a page whose own query already failed, and during a
 * build with no DATABASE_URL at all. Neither is a reason to lose the
 * navigation, so this returns null rather than throwing.
 */
export async function loadNavCounts(): Promise<NavCounts | null> {
  try {
    return await getNavCounts();
  } catch (error) {
    // Same rule as every other query here: the driver error can carry the host
    // and role from the connection string, so it stays in the server log.
    console.error("nav count query failed", error);
    return null;
  }
}
