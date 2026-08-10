import type { ReactNode } from "react";
import { Sidebar, type NavSection } from "./sidebar";
import { loadNavCounts } from "./nav-counts";

/**
 * Sidebar plus main column, wrapped around every route.
 *
 * `current` is passed by the page rather than read from the URL. The obvious
 * alternative is usePathname(), which would make this the dashboard's first
 * client component and ship JavaScript to recover something the page already
 * knows at render time.
 *
 * The nav counts are one more round trip, and it lands after the page's own
 * query rather than beside it -- a page awaits its data before it can return
 * this component. Parallelising it properly would mean moving every page's
 * fetch into a suspended child and writing a skeleton for each, which is a lot
 * of machinery for a query that costs single-digit milliseconds against a read
 * path already measured at a quarter of a second.
 *
 * Below the large breakpoint the sidebar becomes a horizontal bar rather than a
 * drawer: a drawer means state, and there are four links.
 */
export async function Shell({
  current,
  queryMs,
  children,
}: {
  current: NavSection;
  queryMs?: number;
  children: ReactNode;
}) {
  const counts = await loadNavCounts();

  return (
    <div className="mx-auto flex min-h-screen w-full max-w-[1400px] flex-col lg:flex-row">
      <aside className="border-b border-edge px-5 py-4 lg:sticky lg:top-0 lg:h-screen lg:w-[210px] lg:shrink-0 lg:border-b-0 lg:border-r lg:px-4 lg:py-6">
        <Sidebar current={current} counts={counts} queryMs={queryMs} />
      </aside>
      <main className="min-w-0 flex-1 px-5 py-7 sm:px-8 sm:py-10">
        {children}
      </main>
    </div>
  );
}

export type { NavSection };
