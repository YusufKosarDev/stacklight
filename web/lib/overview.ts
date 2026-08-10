import type { GroupSummary } from "@/lib/queries";

export type OverviewSummary = {
  openCount: number;
  regressedCount: number;
  resolvedCount: number;
  /**
   * Counted so the tally above the list adds up to the list. Ignored groups are
   * still listed; leaving them out of the counts made the header look wrong.
   */
  ignoredCount: number;
  events24h: number;
  /** 24 hourly totals across every group, oldest first. */
  hourly: number[];
};

/**
 * Everything the overview tiles and the aggregate chart need, from data the
 * page has already fetched.
 *
 * Deliberately not a query. listGroups() and listSparklines() are both on the
 * critical read path already, and asking the database to count rows it just
 * handed over would add round trips to the page whose speed is the argument
 * this dashboard exists to make.
 */
export function summarise(
  groups: GroupSummary[],
  sparklines: Map<number, number[]>,
): OverviewSummary {
  const hourly = new Array<number>(24).fill(0);
  for (const series of sparklines.values()) {
    for (let hour = 0; hour < 24; hour++) {
      hourly[hour] += series[hour] ?? 0;
    }
  }

  return {
    openCount: groups.filter((g) => g.status === "open").length,
    regressedCount: groups.filter((g) => g.status === "regressed").length,
    resolvedCount: groups.filter((g) => g.status === "resolved").length,
    ignoredCount: groups.filter((g) => g.status === "ignored").length,
    events24h: hourly.reduce((sum, count) => sum + count, 0),
    hourly,
  };
}
