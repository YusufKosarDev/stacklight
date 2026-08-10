# Dashboard Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Stacklight dashboard around a persistent sidebar shell with a deliberate design system, replacing the unstyled scaffold that currently renders in Arial.

**Architecture:** A token layer in `globals.css` feeds Tailwind utilities. Three UI primitives (panel, stat tile, badge) replace markup repeated across four pages. A `Shell` server component wraps every route; each page names its own nav section rather than reading the URL in the browser, so the dashboard keeps shipping zero client JavaScript. Data access is unchanged — server components reading `lib/queries.ts` over Neon HTTP.

**Tech Stack:** Next.js 16 App Router, React 19, Tailwind CSS 4, TypeScript, `@neondatabase/serverless`.

**Spec:** `docs/superpowers/specs/2026-08-10-dashboard-redesign-design.md`

**Branch:** `redesign/dashboard` (already created, spec already committed)

## Global Constraints

- **No new dependencies.** Not runtime, not dev. No shadcn/ui, no Radix, no chart library, no test runner.
- **No client components.** `grep -rn "use client" web/app web/lib` must return nothing at the end of every task.
- **No HTTP from `web/`.** CI's `policy` job greps for `fetch(`, `axios`, `node-fetch`, `undici`, `XMLHttpRequest`, `onrender.com`, `render.com` under `web/`. A match fails the build.
- **`npm run build` must succeed with no `DATABASE_URL`.** Verified after every task.
- **Single dark surface.** No light theme, no `prefers-color-scheme`.
- **Driver errors never reach the page.** They can carry host and role from the connection string. Log server-side, render a generic failure panel.
- **Exact token values** are listed in Task 1 and must be copied verbatim; later tasks reference them by utility name only.

### On testing

This plan has no red-green test cycle, and that is a deliberate reading of the spec rather than an omission. The dashboard has no test framework, and adding one would break the no-new-dependencies rule for changes that are almost entirely presentational. The verification loop below runs at the end of **every** task and is what gates each commit:

```bash
cd web
npm run lint                                    # eslint
npm run build                                   # includes tsc; must pass with no DATABASE_URL
grep -rn "use client" app lib || echo "zero client components"
```

Plus, from the repository root:

```bash
pattern='\bfetch[[:space:]]*\(|\baxios\b|node-fetch|\bundici\b|XMLHttpRequest|onrender\.com|render\.com'
git grep -InE "$pattern" -- web ':!web/package-lock.json' || echo "read path clean"
```

Tasks 5–9 additionally require looking at the rendered page. `web/.env.local` already holds a working `DATABASE_URL`, so `npm run dev` renders against live data.

---

## File Structure

**Create:**

| Path | Responsibility |
|---|---|
| `web/app/components/ui/panel.tsx` | Bordered surface + optional header row |
| `web/app/components/ui/stat-tile.tsx` | Label, big tabular number, optional unit and meter |
| `web/app/components/ui/badge.tsx` | Every chip: level, group status, alert kind, degraded reason |
| `web/app/components/shell/shell.tsx` | Sidebar + main column, takes `current` |
| `web/app/components/shell/sidebar.tsx` | Logo, nav links, read-path footer |
| `web/app/components/shell/nav-counts.tsx` | Async badge numbers, fails independently |
| `web/lib/overview.ts` | Pure derivations for the overview page |

**Modify:**

| Path | Change |
|---|---|
| `web/app/globals.css` | Replace scaffold with tokens; fix the Arial bug |
| `web/app/layout.tsx` | `force-dynamic`, apply `font-sans`, drop hard-coded body colours |
| `web/app/components/charts.tsx` | Retoken to violet; add `OverviewTrend` |
| `web/lib/queries.ts` | Add `getNavCounts()` |
| `web/lib/format.ts` | Delete `levelStyle`, `statusStyle`, `LEVEL_STYLES`, `STATUS_STYLES` |
| `web/app/page.tsx` | Rebuild as Overview |
| `web/app/groups/[id]/page.tsx` | Reorganise into panels |
| `web/app/alerts/page.tsx` | Onto shell; its local `KIND_STYLES` moves into `badge.tsx` |
| `web/app/detectors/page.tsx` | Onto shell and primitives |
| `web/app/how-grouping-works/page.tsx` | Onto shell; prose measure |

---

## Task 1: Design tokens and the Arial fix

**Files:**
- Modify: `web/app/globals.css` (whole file)
- Modify: `web/app/layout.tsx` (whole file)

**Interfaces:**
- Consumes: nothing.
- Produces: Tailwind colour utilities `surface-0`, `surface-1`, `surface-2`, `edge`, `edge-strong`, `ink-hi`, `ink`, `ink-low`, `ink-faint`, `accent`, `accent-hi`, `accent-lo`, `danger`, `danger-bg`, `danger-edge`, `warn`, `warn-bg`, `ok`. Usable as `bg-*`, `text-*`, `border-*`, `ring-*`. Also `font-sans` and `font-mono`.

- [ ] **Step 1: Confirm the bug before fixing it**

```bash
cd web && grep -n "font-family" app/globals.css
```

Expected: `font-family: Arial, Helvetica, sans-serif;` on the `body` rule. This is what makes every page render in Arial despite Geist being loaded.

- [ ] **Step 2: Replace `web/app/globals.css` entirely**

```css
@import "tailwindcss";

/*
 * Design tokens.
 *
 * The dashboard ships one dark surface and nothing else, so there is no light
 * palette and no prefers-color-scheme block. Both were Next.js scaffold
 * leftovers describing a theme this application never renders.
 */
@theme {
  --color-surface-0: #09090b;
  --color-surface-1: rgba(255, 255, 255, 0.025);
  --color-surface-2: rgba(255, 255, 255, 0.045);

  --color-edge: #1f1f28;
  --color-edge-strong: #2a2a36;

  --color-ink-hi: #ededf2;
  --color-ink: #c9c9d2;
  --color-ink-low: #7a7a88;
  --color-ink-faint: #57575f;

  --color-accent: #7c5cff;
  --color-accent-hi: #9b7cff;
  --color-accent-lo: #6344e8;

  --color-danger: #ff8080;
  --color-danger-bg: rgba(255, 90, 90, 0.13);
  --color-danger-edge: rgba(255, 90, 90, 0.24);
  --color-warn: #ffc46b;
  --color-warn-bg: rgba(255, 180, 60, 0.13);
  --color-ok: #3dd68c;
}

/*
 * Inline so the utility emits the next/font variable directly. Without it the
 * font utilities resolve through a second indirection that next/font does not
 * populate, which is half of why the page was falling back to Arial.
 */
@theme inline {
  --font-sans: var(--font-geist-sans);
  --font-mono: var(--font-geist-mono);
}

body {
  background-color: var(--color-surface-0);
  /* Fixed, so the glow stays anchored to the viewport instead of sliding away
     up a long group list. */
  background-image: radial-gradient(
    100% 70% at 0% 0%,
    #17131f 0%,
    transparent 55%
  );
  background-attachment: fixed;
  color: var(--color-ink);
}
```

- [ ] **Step 3: Replace `web/app/layout.tsx` entirely**

```tsx
import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Stacklight",
  description: "Error ingestion and triage",
};

/*
 * Every route renders per request.
 *
 * Declared here rather than per page because the shell reads the database for
 * its nav counts. Without it Next would try to prerender /how-grouping-works at
 * build time, and CI builds with no DATABASE_URL on purpose. The cost is that
 * the one static page becomes a per-request render.
 */
export const dynamic = "force-dynamic";

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full font-sans text-ink">{children}</body>
    </html>
  );
}
```

- [ ] **Step 4: Run the verification loop**

```bash
cd web && npm run lint && npm run build
```

Expected: both pass. The build must succeed even though `DATABASE_URL` is absent from the shell environment; every route should now be listed as `ƒ (Dynamic)`, including `/how-grouping-works`.

- [ ] **Step 5: Confirm the font is actually applied**

```bash
cd web && npm run dev
```

Open `http://localhost:3000`, inspect `<body>`, and confirm computed `font-family` starts with a Geist face rather than Arial. Stop the server.

- [ ] **Step 6: Commit**

```bash
git add web/app/globals.css web/app/layout.tsx
git commit -m "fix(web): apply the font the dashboard already loads"
```

Message body:

```
globals.css set Arial on the body while layout.tsx loaded Geist Sans and Geist
Mono and applied them nowhere, so every page has been rendering in Arial since
the scaffold. The same file still carried a light --background and a
prefers-color-scheme block for a theme this dashboard never renders.

Replaced with the token set the redesign is built on, and force-dynamic moves to
the root layout: the shell reads the database for its nav counts, and without it
Next would try to prerender /how-grouping-works at build time, which CI runs
without DATABASE_URL on purpose.
```

---

## Task 2: UI primitives

**Files:**
- Create: `web/app/components/ui/panel.tsx`
- Create: `web/app/components/ui/stat-tile.tsx`
- Create: `web/app/components/ui/badge.tsx`

**Interfaces:**
- Consumes: Task 1 utilities.
- Produces:
  - `Panel({ children, className })`, `PanelHeader({ title, aside })`
  - `StatTile({ label, value, unit, meter })` where `meter` is `{ fraction: number; caption: string }`
  - `LevelBadge({ level })`, `StatusBadge({ status })`, `AlertKindBadge({ kind })`, `DegradedBadge({ reason })`

Nothing consumes these yet; they are wired up from Task 5 onward.

- [ ] **Step 1: Create `web/app/components/ui/panel.tsx`**

```tsx
import type { ReactNode } from "react";

/**
 * The bordered surface every card on the dashboard is made of.
 *
 * One component rather than the same four utility classes repeated on every
 * page, which is what the dashboard had and why nothing ever moved together.
 */
export function Panel({
  children,
  className = "",
}: {
  children: ReactNode;
  className?: string;
}) {
  return (
    <section
      className={`rounded-xl border border-edge bg-surface-1 p-4 sm:p-5 ${className}`}
    >
      {children}
    </section>
  );
}

/** Title on the left, a secondary figure on the right. */
export function PanelHeader({
  title,
  aside,
}: {
  title: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
      <h2 className="text-sm font-medium text-ink">{title}</h2>
      {aside && (
        <span className="font-mono text-xs tabular-nums text-ink-low">
          {aside}
        </span>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Create `web/app/components/ui/stat-tile.tsx`**

```tsx
/**
 * One headline number.
 *
 * The meter is optional and exists for storage, where the number only means
 * something against the plan limit that would suspend the project.
 */
export function StatTile({
  label,
  value,
  unit,
  meter,
}: {
  label: string;
  value: string | number;
  unit?: string;
  meter?: { fraction: number; caption: string };
}) {
  return (
    <div className="relative overflow-hidden rounded-xl border border-edge bg-surface-1 p-4">
      {/* A hairline of accent along the top edge, fading out. Enough to make the
          tiles read as a set without adding another border colour. */}
      <span
        aria-hidden
        className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-accent/50 to-transparent"
      />
      <span className="block text-[10px] font-medium uppercase tracking-[0.09em] text-ink-low">
        {label}
      </span>
      <span className="mt-1 block text-2xl font-semibold tracking-tight tabular-nums text-ink-hi">
        {value}
        {unit && <span className="ml-1 text-sm text-ink-low">{unit}</span>}
      </span>
      {meter && (
        <>
          <div className="mt-3 h-1 overflow-hidden rounded-full bg-edge">
            <div
              className="h-full rounded-full bg-gradient-to-r from-accent-lo to-accent-hi"
              style={{ width: `${Math.max(1, Math.min(100, meter.fraction * 100))}%` }}
            />
          </div>
          <span className="mt-1.5 block text-[10px] text-ink-low">
            {meter.caption}
          </span>
        </>
      )}
    </div>
  );
}
```

- [ ] **Step 3: Create `web/app/components/ui/badge.tsx`**

```tsx
import type { ReactNode } from "react";
import type { GroupStatus } from "@/lib/queries";

/**
 * Every chip on the dashboard.
 *
 * These were three separate lookup maps returning raw class strings --
 * LEVEL_STYLES and STATUS_STYLES in lib/format.ts and KIND_STYLES inside the
 * alerts page -- each leaving the call site to assemble the element by hand.
 * That is why the same chip markup appeared on four pages and drifted between
 * them.
 */
function Chip({
  className,
  icon,
  children,
}: {
  className: string;
  icon?: string;
  children: ReactNode;
}) {
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${className}`}
    >
      {icon && <span aria-hidden>{icon}</span>}
      {children}
    </span>
  );
}

const LEVELS: Record<string, string> = {
  ERROR: "bg-danger-bg text-danger",
  WARN: "bg-warn-bg text-warn",
  INFO: "bg-accent/12 text-accent-hi",
};

export function LevelBadge({ level }: { level: string }) {
  const key = level.toUpperCase();
  return (
    <Chip className={LEVELS[key] ?? "bg-surface-2 text-ink-low"}>
      {level.toLowerCase()}
    </Chip>
  );
}

/**
 * `regressed` is deliberately the loudest of the four: a group that came back
 * after being called fixed is a worse signal than one nobody has looked at yet.
 * The icon carries the distinction too, so it never rests on colour alone.
 */
const STATUSES: Record<string, { label: string; icon: string; className: string }> = {
  open: { label: "open", icon: "●", className: "bg-surface-2 text-ink-low" },
  resolved: { label: "resolved", icon: "✓", className: "bg-ok/12 text-ok" },
  ignored: { label: "ignored", icon: "◌", className: "bg-surface-2 text-ink-faint" },
  regressed: { label: "regressed", icon: "↺", className: "bg-danger-bg text-danger" },
};

export function StatusBadge({ status }: { status: GroupStatus | string }) {
  const style = STATUSES[status] ?? STATUSES.open;
  return (
    <Chip className={style.className} icon={style.icon}>
      {style.label}
    </Chip>
  );
}

const KINDS: Record<string, { label: string; icon: string; className: string }> = {
  spike: { label: "spike", icon: "▲", className: "bg-warn-bg text-warn" },
  new_group: { label: "new error", icon: "✦", className: "bg-accent/12 text-accent-hi" },
  regression: { label: "regression", icon: "↺", className: "bg-danger-bg text-danger" },
};

export function AlertKindBadge({ kind }: { kind: string }) {
  const style = KINDS[kind] ?? { label: kind, icon: "•", className: "bg-surface-2 text-ink-low" };
  return (
    <Chip className={style.className} icon={style.icon}>
      {style.label}
    </Chip>
  );
}

/** Says grouping had to fall back to weaker signal than in-app frames. */
export function DegradedBadge({ reason }: { reason: string }) {
  return (
    <Chip className="bg-warn-bg font-mono text-warn">{reason}</Chip>
  );
}
```

- [ ] **Step 4: Run the verification loop**

```bash
cd web && npm run lint && npm run build
grep -rn "use client" app lib || echo "zero client components"
```

Expected: lint and build pass; the grep prints the reassurance line. The new files compile but are not yet imported, which is fine.

- [ ] **Step 5: Commit**

```bash
git add web/app/components/ui
git commit -m "feat(web): add the panel, stat tile and badge primitives"
```

Message body:

```
Three chip lookup maps existed -- LEVEL_STYLES and STATUS_STYLES in format.ts
and KIND_STYLES inside the alerts page -- and each returned a raw class string,
leaving every call site to build the element itself. That is why the same chip
markup sat on four pages and had already drifted between them.

Not wired up yet; the pages move over one at a time.
```

---

## Task 3: The shell

**Files:**
- Create: `web/app/components/shell/nav-counts.tsx`
- Create: `web/app/components/shell/sidebar.tsx`
- Create: `web/app/components/shell/shell.tsx`
- Modify: `web/lib/queries.ts` (append `getNavCounts` and its type)

**Interfaces:**
- Consumes: Task 1 utilities.
- Produces:
  - `getNavCounts(): Promise<NavCounts>` where `type NavCounts = { open_groups: number; recent_alerts: number }`
  - `Shell({ current, queryMs, children })` where `current` is `"groups" | "alerts" | "detectors" | "grouping"` and `queryMs` is `number | undefined`
  - `type NavSection = "groups" | "alerts" | "detectors" | "grouping"`

- [ ] **Step 1: Append `getNavCounts` to `web/lib/queries.ts`**

Add at the end of the file:

```ts
export type NavCounts = { open_groups: number; recent_alerts: number };

/**
 * The two numbers the sidebar carries, in one round trip.
 *
 * Counted rather than derived from listGroups() because the sidebar renders on
 * every route, including the ones that never load a group list.
 */
export async function getNavCounts(): Promise<NavCounts> {
  const rows = (await sql()`
    select (select count(*)::int from event_groups
             where status in ('open', 'regressed'))            as open_groups,
           (select count(*)::int from alerts
             where created_at > now() - interval '7 days')     as recent_alerts
  `) as NavCounts[];

  return rows[0];
}
```

- [ ] **Step 2: Create `web/app/components/shell/nav-counts.tsx`**

```tsx
import { getNavCounts, type NavCounts } from "@/lib/queries";

/**
 * The sidebar's badge numbers, fetched separately so they can fail alone.
 *
 * The nav has to render on a page whose own query already failed, and on a
 * build with no DATABASE_URL at all. Neither is a reason to lose the
 * navigation, so this returns nulls rather than throwing.
 */
export async function loadNavCounts(): Promise<NavCounts | null> {
  try {
    return await getNavCounts();
  } catch (error) {
    console.error("nav count query failed", error);
    return null;
  }
}
```

- [ ] **Step 3: Create `web/app/components/shell/sidebar.tsx`**

```tsx
import Link from "next/link";
import type { NavCounts } from "@/lib/queries";

export type NavSection = "groups" | "alerts" | "detectors" | "grouping";

const LINKS: { key: NavSection; href: string; label: string }[] = [
  { key: "groups", href: "/", label: "Groups" },
  { key: "alerts", href: "/alerts", label: "Alerts" },
  { key: "detectors", href: "/detectors", label: "Detectors" },
  { key: "grouping", href: "/how-grouping-works", label: "How grouping works" },
];

function countFor(key: NavSection, counts: NavCounts | null): number | null {
  if (!counts) return null;
  if (key === "groups") return counts.open_groups;
  if (key === "alerts") return counts.recent_alerts;
  return null;
}

export function Sidebar({
  current,
  counts,
  queryMs,
}: {
  current: NavSection;
  counts: NavCounts | null;
  queryMs?: number;
}) {
  return (
    <div className="flex h-full flex-col gap-6 lg:gap-0">
      <Link href="/" className="flex items-center gap-2.5">
        <span
          aria-hidden
          className="h-4 w-4 shrink-0 rounded-[5px] bg-gradient-to-br from-accent-hi to-accent-lo shadow-[0_0_14px_rgba(124,92,255,0.5)]"
        />
        <span className="text-[13px] font-semibold tracking-tight text-ink-hi">
          Stacklight
        </span>
      </Link>

      <nav className="flex flex-1 gap-1 overflow-x-auto lg:mt-5 lg:flex-col lg:overflow-visible">
        {LINKS.map((link) => {
          const active = link.key === current;
          const count = countFor(link.key, counts);
          return (
            <Link
              key={link.key}
              href={link.href}
              aria-current={active ? "page" : undefined}
              className={`flex shrink-0 items-center gap-2 whitespace-nowrap rounded-lg px-3 py-2 text-[13px] transition-colors ${
                active
                  ? "bg-accent/15 text-ink-hi ring-1 ring-inset ring-accent/25"
                  : "text-ink-low hover:bg-surface-2 hover:text-ink"
              }`}
            >
              {link.label}
              {count !== null && (
                <span className="ml-auto rounded-full bg-surface-2 px-1.5 text-[10px] tabular-nums text-ink-low">
                  {count}
                </span>
              )}
            </Link>
          );
        })}
      </nav>

      {/*
        The architectural claim, on every page. The dashboard talks to Postgres
        and nothing else, which is what lets it render in full while the
        ingestion service is asleep.
      */}
      <div className="hidden border-t border-edge pt-4 lg:block">
        <span className="block text-[10px] font-medium uppercase tracking-[0.09em] text-ink-faint">
          Read path
        </span>
        <span className="mt-1.5 flex items-center gap-2 text-[11px] text-ink">
          <span
            aria-hidden
            className="h-1.5 w-1.5 rounded-full bg-ok shadow-[0_0_7px_rgba(61,214,140,0.8)]"
          />
          Postgres{queryMs !== undefined && ` · ${(queryMs / 1000).toFixed(2)} s`}
        </span>
        <span className="mt-1 block text-[10px] leading-relaxed text-ink-faint">
          Renders whether or not the ingestion service is awake.
        </span>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Create `web/app/components/shell/shell.tsx`**

```tsx
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
      <main className="min-w-0 flex-1 px-5 py-7 sm:px-8 sm:py-10">{children}</main>
    </div>
  );
}

export type { NavSection };
```

- [ ] **Step 5: Run the verification loop**

```bash
cd web && npm run lint && npm run build
grep -rn "use client" app lib || echo "zero client components"
```

Expected: all pass. The shell is not yet used by any route.

- [ ] **Step 6: Commit**

```bash
git add web/app/components/shell web/lib/queries.ts
git commit -m "feat(web): add the application shell"
```

Message body:

```
A sidebar that carries the nav, live counts for groups and alerts, and the
read-path status. The last of those is the point: the claim that this dashboard
renders while the ingestion service sleeps now sits on every page rather than in
a card on one of them.

Active state comes from the page naming its own section. usePathname would have
made this the first client component in web/ and shipped JavaScript to work out
something the page already knows.

The counts fetch separately and swallow their own failure, because the nav has
to render on a page whose query already failed and in a build with no
DATABASE_URL.
```

---

## Task 4: Charts on the new palette

**Files:**
- Modify: `web/app/components/charts.tsx`

**Interfaces:**
- Consumes: Task 1 utilities; `Bucket` from `@/lib/queries`.
- Produces: `Sparkline({ series, label })` unchanged in signature; `TrendChart({ buckets, range })` unchanged in signature; new `OverviewTrend({ hourly, total })` where `hourly` is `number[]` of length 24, oldest first.

- [ ] **Step 1: Check the contrast of the new series colour before using it**

The file currently documents its blue as "the validated dark-mode blue; it clears 3:1 against the page surface (#09090b)". The replacement has to earn the same sentence rather than inherit it.

Compute the contrast ratio of `#7c5cff` against `#09090b`:

```bash
node -e '
const lin = c => { c/=255; return c<=0.03928 ? c/12.92 : Math.pow((c+0.055)/1.055,2.4); };
const L = h => { const n=parseInt(h.slice(1),16);
  return 0.2126*lin(n>>16&255)+0.7152*lin(n>>8&255)+0.0722*lin(n&255); };
const ratio=(a,b)=>{const x=L(a),y=L(b);return ((Math.max(x,y)+0.05)/(Math.min(x,y)+0.05)).toFixed(2);};
console.log("accent   #7c5cff vs #09090b:", ratio("#7c5cff","#09090b"));
console.log("accentHi #9b7cff vs #09090b:", ratio("#9b7cff","#09090b"));
console.log("muted    #46356e vs #09090b:", ratio("#46356e","#09090b"));
'
```

Record the printed numbers — they go into the comment in Step 2. `#7c5cff` and `#9b7cff` must both be at least 3.0. If the muted step comes in below 3.0 that is acceptable: it is a de-emphasised fill behind a labelled total, not a mark carrying meaning on its own, and the comment must say so.

- [ ] **Step 2: Replace the token block at the top of `charts.tsx`**

Replace lines 1–13 (the imports and the four `const` colour tokens) with:

```tsx
import type { Bucket } from "@/lib/queries";

/*
 * Chart tokens.
 *
 * The dashboard ships one dark surface, so only the dark steps exist. Contrast
 * against the page surface (#09090b) was measured rather than assumed:
 * SERIES clears 3:1 and SERIES_HI clears it comfortably. SERIES_MUTED sits
 * below 3:1 on purpose -- it is a de-emphasised fill behind a labelled total,
 * never a mark that has to be read on its own.
 */
const SERIES = "#7c5cff";
const SERIES_HI = "#9b7cff";
const SERIES_MUTED = "#46356e";
const MUTED_INK = "#7a7a88";
const GRIDLINE = "#1f1f28";
```

- [ ] **Step 3: Point the existing charts at the new tokens**

In `Sparkline`, the bar background currently reads:

```tsx
background: count === 0 ? GRIDLINE : isCurrent ? SERIES : SERIES_MUTED,
```

Change it to:

```tsx
background: count === 0 ? GRIDLINE : isCurrent ? SERIES_HI : SERIES,
```

The most recent hour is the one worth picking out, and it now does so by getting brighter rather than by being the only saturated bar.

In `TrendChart`, the column background currently reads:

```tsx
background: bucket.count === 0 ? GRIDLINE : SERIES,
```

Leave that line as it is — `SERIES` now resolves to the violet. Change the tooltip's border and background classes from `border-zinc-700 bg-zinc-950` to `border-edge bg-surface-0`, the `text-zinc-200` to `text-ink`, the figcaption's `text-zinc-400` to `text-ink-low`, the `details` summary's `text-zinc-500`/`hover:text-zinc-300` to `text-ink-low`/`hover:text-ink`, the table wrapper's `border-zinc-800` to `border-edge`, `bg-zinc-900` to `bg-surface-1`, `text-zinc-500` to `text-ink-low`, `text-zinc-300` to `text-ink`, and `border-zinc-800/60` to `border-edge`.

- [ ] **Step 4: Append `OverviewTrend` to `charts.tsx`**

```tsx
/**
 * Every group's events, by hour, for the last day.
 *
 * Takes an already-summed array rather than querying: the per-group rollups are
 * fetched for the sparklines anyway, so the aggregate is a sum over data the
 * page is holding rather than a second trip to the database on the critical
 * read path.
 *
 * @param hourly 24 counts, oldest first
 */
export function OverviewTrend({
  hourly,
  total,
}: {
  hourly: number[];
  total: number;
}) {
  const peak = Math.max(...hourly, 1);
  const peakIndex = hourly.findIndex((count) => count === peak && peak > 0);

  return (
    <figure className="m-0">
      <figcaption className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <span className="text-sm font-medium text-ink">
          All events, last 24 hours
        </span>
        <span className="font-mono text-xs tabular-nums text-ink-low">
          {total} total &middot; peak {peak}
        </span>
      </figcaption>

      <div
        className="flex h-24 items-end gap-[3px] border-b"
        style={{ borderColor: GRIDLINE }}
        role="img"
        aria-label={`${total} events over the last 24 hours, peaking at ${peak} in one hour`}
      >
        {hourly.map((count, index) => (
          <span
            key={index}
            className="flex-1 rounded-t-[3px]"
            style={{
              height: count === 0 ? "2px" : `${Math.max(4, (count / peak) * 92)}px`,
              background:
                count === 0 ? GRIDLINE : index === peakIndex ? SERIES_HI : SERIES,
            }}
          />
        ))}
      </div>

      <div className="mt-2 flex justify-between font-mono text-[10px] tabular-nums text-ink-faint">
        <span>24h ago</span>
        <span>12h</span>
        <span>now</span>
      </div>
    </figure>
  );
}
```

- [ ] **Step 5: Run the verification loop**

```bash
cd web && npm run lint && npm run build
```

Expected: both pass.

- [ ] **Step 6: Commit**

```bash
git add web/app/components/charts.tsx
git commit -m "feat(web): move the charts onto the accent palette"
```

Message body:

```
The series colour was a blue chosen for the old surface, and its comment claimed
a measured 3:1 contrast. The violet replacements were measured the same way
rather than inheriting the sentence, and the muted step is documented as
deliberately below the bar because it is a de-emphasised fill rather than a mark
that has to be read alone.

Adds OverviewTrend, the aggregate day for the overview page. It takes an
already-summed array instead of querying: the per-group rollups are fetched for
the sparklines anyway, so the aggregate costs no second trip on the read path.
```

---

## Task 5: The overview page

**Files:**
- Create: `web/lib/overview.ts`
- Modify: `web/app/page.tsx` (whole file)

**Interfaces:**
- Consumes: `Shell` (Task 3), `Panel`/`PanelHeader`, `StatTile`, `LevelBadge`/`StatusBadge`/`DegradedBadge` (Task 2), `Sparkline`/`OverviewTrend` (Task 4).
- Produces: `summarise(groups, sparklines): OverviewSummary` where `OverviewSummary = { openCount: number; regressedCount: number; resolvedCount: number; events24h: number; hourly: number[] }`.

- [ ] **Step 1: Create `web/lib/overview.ts`**

```ts
import type { GroupSummary } from "@/lib/queries";

export type OverviewSummary = {
  openCount: number;
  regressedCount: number;
  resolvedCount: number;
  events24h: number;
  /** 24 hourly totals across every group, oldest first. */
  hourly: number[];
};

/**
 * Everything the overview tiles and the aggregate chart need, from data the
 * page has already fetched.
 *
 * Deliberately not a query. listGroups() and listSparklines() are both on the
 * critical read path already; asking the database for counts it just handed
 * over would add round trips to the page whose speed is the whole argument.
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
    events24h: hourly.reduce((sum, count) => sum + count, 0),
    hourly,
  };
}
```

- [ ] **Step 2: Replace `web/app/page.tsx` entirely**

```tsx
import Link from "next/link";
import {
  listGroups,
  listSparklines,
  getStorageStatus,
  type GroupSummary,
  type StorageStatus,
} from "@/lib/queries";
import { summarise, type OverviewSummary } from "@/lib/overview";
import { relativeTime, formatBytes } from "@/lib/format";
import { Shell } from "@/app/components/shell/shell";
import { Panel } from "@/app/components/ui/panel";
import { StatTile } from "@/app/components/ui/stat-tile";
import { LevelBadge, StatusBadge, DegradedBadge } from "@/app/components/ui/badge";
import { Sparkline, OverviewTrend } from "@/app/components/charts";

/** The plan suspends the project at this point rather than billing for it. */
const STORAGE_LIMIT = 512 * 1024 * 1024;

type LoadResult =
  | {
      ok: true;
      groups: GroupSummary[];
      sparklines: Map<number, number[]>;
      storage: StorageStatus;
      summary: OverviewSummary;
      ms: number;
    }
  | { ok: false; ms: number };

async function load(): Promise<LoadResult> {
  const started = Date.now();
  try {
    const [groups, sparklines, storage] = await Promise.all([
      listGroups(),
      listSparklines(),
      getStorageStatus(),
    ]);
    return {
      ok: true,
      groups,
      sparklines,
      storage,
      summary: summarise(groups, sparklines),
      ms: Date.now() - started,
    };
  } catch (error) {
    // The driver error can carry the host and role from the connection string,
    // so it stays in the server log and never reaches the page.
    console.error("group query failed", error);
    return { ok: false, ms: Date.now() - started };
  }
}

function GroupRow({
  group,
  series,
}: {
  group: GroupSummary;
  series: number[] | undefined;
}) {
  const regressed = group.status === "regressed";

  return (
    <li>
      <Link
        href={`/groups/${group.id}`}
        className={`flex items-center gap-3 rounded-xl border p-3.5 transition-colors sm:gap-4 sm:p-4 ${
          regressed
            ? "border-danger-edge bg-danger-bg/25 hover:border-danger/40"
            : "border-edge bg-surface-1 hover:border-edge-strong hover:bg-surface-2"
        }`}
      >
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            {regressed ? (
              <StatusBadge status={group.status} />
            ) : (
              <LevelBadge level={group.level} />
            )}
            <h3 className="min-w-0 flex-1 truncate text-sm text-ink-hi">
              {group.title}
            </h3>
          </div>
          <p className="mt-1 truncate font-mono text-[11px] text-ink-low">
            {group.culprit ?? "no frame attributed"}
            {" · "}
            {group.service}
            {" · "}
            {group.platform}
          </p>
          {group.degraded_reason && (
            <p className="mt-1.5">
              <DegradedBadge reason={group.degraded_reason} />
            </p>
          )}
        </div>

        <div className="hidden sm:block">
          <Sparkline series={series} label={group.title} />
        </div>

        <div className="shrink-0 text-right">
          <span className="block text-base font-semibold tabular-nums text-ink-hi">
            {group.event_count}
          </span>
          <span className="block text-[11px] text-ink-low">
            {relativeTime(group.last_seen)}
          </span>
        </div>
      </Link>
    </li>
  );
}

export default async function Page() {
  const result = await load();

  return (
    <Shell current="groups" queryMs={result.ms}>
      <header className="mb-7">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          Overview
        </h1>
        <p className="mt-1 text-sm text-ink-low">
          Errors grouped by fingerprint, newest first.
        </p>
      </header>

      {!result.ok && (
        <Panel className="border-danger-edge bg-danger-bg/20">
          <p className="text-sm text-danger">
            The group query failed. Details are in the server log.
          </p>
        </Panel>
      )}

      {result.ok && (
        <>
          <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatTile label="Open faults" value={result.summary.openCount} />
            <StatTile label="Events · 24h" value={result.summary.events24h} />
            <StatTile label="Regressed" value={result.summary.regressedCount} />
            <StatTile
              label="Storage"
              value={formatBytes(result.storage.total_bytes).replace(/ .*/, "")}
              unit={formatBytes(result.storage.total_bytes).split(" ")[1]}
              meter={{
                fraction: result.storage.total_bytes / STORAGE_LIMIT,
                caption: `of 512 MB · ${
                  result.storage.last_sweep_at
                    ? `swept ${relativeTime(result.storage.last_sweep_at)}`
                    : "not swept yet"
                }`,
              }}
            />
          </div>

          <Panel className="mb-7">
            <OverviewTrend
              hourly={result.summary.hourly}
              total={result.summary.events24h}
            />
          </Panel>

          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-sm font-medium text-ink">Groups</h2>
            <span className="font-mono text-xs tabular-nums text-ink-low">
              {result.summary.openCount} open · {result.summary.regressedCount}{" "}
              regressed · {result.summary.resolvedCount} resolved
            </span>
          </div>

          {result.groups.length === 0 ? (
            <Panel>
              <p className="text-sm text-ink-low">
                No groups yet. Send an event to{" "}
                <code className="font-mono text-ink">POST /api/events</code> on the
                ingestion service.
              </p>
            </Panel>
          ) : (
            <ul className="space-y-2">
              {result.groups.map((group) => (
                <GroupRow
                  key={group.id}
                  group={group}
                  series={result.sparklines.get(group.id)}
                />
              ))}
            </ul>
          )}
        </>
      )}
    </Shell>
  );
}
```

- [ ] **Step 3: Run the verification loop**

```bash
cd web && npm run lint && npm run build
grep -rn "use client" app lib || echo "zero client components"
```

Expected: all pass.

- [ ] **Step 4: Look at it against real data**

```bash
cd web && npm run dev
```

Open `http://localhost:3000` and confirm:
- Four stat tiles, and the events-24h figure equals the aggregate chart's stated total.
- The storage meter is a thin sliver (roughly 1.5% of the limit) rather than empty or full.
- Any regressed group renders with the red-tinted row.
- The sidebar shows a count next to Groups and Alerts, and the read-path line shows a time.
- At 390px wide the sidebar is a horizontal bar, the tiles are two-up, sparklines are hidden, and the page does not scroll sideways.

Stop the server.

- [ ] **Step 5: Commit**

```bash
git add web/lib/overview.ts web/app/page.tsx
git commit -m "feat(web): rebuild the front page as an overview"
```

Message body:

```
Stat tiles, the aggregate day, then the groups. What someone landing on the live
URL sees first is now whether the system is alive and busy, rather than a list
with no context around it.

Every tile value is derived from data the page already fetches. summarise() is a
pure function over listGroups() and listSparklines() rather than a query,
because both are already on the critical read path and the speed of that path is
the argument this whole dashboard exists to make.

Regressed groups get a red-tinted row. Of the four states it is the one that
should shout: a fault that came back after being called fixed is worse news than
one nobody has looked at yet.
```

---

## Task 6: The group detail page

**Files:**
- Modify: `web/app/groups/[id]/page.tsx` (whole file)

**Interfaces:**
- Consumes: `Shell`, `Panel`, `PanelHeader`, `StatTile`, badges, `TrendChart`.
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Read the current file end to end before changing it**

```bash
cd web && cat "app/groups/[id]/page.tsx"
```

It is 343 lines and holds content that must survive verbatim: the `frameLabel` helper, the range switcher links, the fingerprint-input block, the frame breakdown with its in-app/vendor split, the similar-groups list with its "suggestions, nothing merges on its own" wording, and the `DEGRADED_REASONS` explanations. This task restyles the container, not the content.

- [ ] **Step 2: Restructure the page**

Keep every existing data call (`getGroup`, `findSimilarGroups`, `getGroupSeries`), the `notFound()` guards, and the `RANGES` array exactly as they are. Change the outer structure to:

```tsx
  return (
    <Shell current="groups">
      {/* header block */}
      <header className="mb-6">
        <div className="flex flex-wrap items-center gap-2">
          <LevelBadge level={group.level} />
          <StatusBadge status={group.status} />
          <span className="font-mono text-xs text-ink-low">{group.service}</span>
          <span className="text-xs text-ink-faint">{group.platform}</span>
        </div>
        <h1 className="mt-2.5 text-lg font-semibold leading-snug tracking-tight text-ink-hi sm:text-xl">
          {group.title}
        </h1>
        <p className="mt-1 break-all font-mono text-xs text-ink-low">
          {group.culprit ?? "no frame attributed"}
        </p>
      </header>

      {/* stat row */}
      <div className="mb-6 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatTile label="Events" value={group.event_count} />
        <StatTile label="First seen" value={relativeTime(group.first_seen)} />
        <StatTile label="Last seen" value={relativeTime(group.last_seen)} />
        <StatTile
          label="Release"
          value={group.release_last ?? group.release_first ?? "—"}
        />
      </div>

      {/* each following block becomes its own <Panel className="mb-5"> */}
    </Shell>
  );
```

Then wrap each existing section in `<Panel className="mb-5">` with a `<PanelHeader title="…" />`, in this order:

1. **Trend** — the range switcher links plus `<TrendChart …/>`. Restyle the range links: active gets `bg-accent/15 text-ink-hi ring-1 ring-inset ring-accent/25`, inactive gets `text-ink-low hover:bg-surface-2 hover:text-ink`, both `rounded-lg px-2.5 py-1 text-xs`.
2. **Sampling notice** — only when `group.sampled_count > 0`. Keep the existing wording about the trend staying complete while stack traces behind part of it are gone.
3. **Why this group** — the degraded-reason explanation from `DEGRADED_REASONS`, only when `group.degraded_reason` is set.
4. **Fingerprint** — `group.fingerprint`, `group.fingerprint_version`, and the `fingerprint_input` in a `<pre>`. Restyle the `<pre>` to `overflow-x-auto rounded-lg border border-edge bg-surface-0 p-3 font-mono text-[11px] leading-relaxed text-ink`.
5. **Frames** — the existing in-app/vendor breakdown. In-app rows get `text-ink`, vendor rows `text-ink-faint`, and the count line stays.
6. **Sample stack trace** — the `<pre>`, same restyle as the fingerprint block.
7. **Similar groups** — the existing list and its wording.
8. **Alerts for this group** — the existing list, using `AlertKindBadge`.

Replace every remaining `zinc` class in the file with the equivalent token: `text-zinc-100`→`text-ink-hi`, `text-zinc-200`/`text-zinc-300`→`text-ink`, `text-zinc-400`/`text-zinc-500`→`text-ink-low`, `text-zinc-600`→`text-ink-faint`, `border-zinc-800`→`border-edge`, `border-zinc-700`→`border-edge-strong`, `bg-zinc-900/40`→`bg-surface-1`, `bg-zinc-950`→`bg-surface-0`.

Delete the `← All groups` link at the top: the sidebar is the navigation now.

- [ ] **Step 3: Verify no zinc classes survive in this file**

```bash
cd web && grep -n "zinc" "app/groups/[id]/page.tsx" || echo "no zinc left"
```

Expected: `no zinc left`.

- [ ] **Step 4: Run the verification loop**

```bash
cd web && npm run lint && npm run build
```

- [ ] **Step 5: Look at it against real data**

```bash
cd web && npm run dev
```

Open a group from the overview and confirm: all three range links work and mark the active one; the fingerprint input renders as preformatted text without breaking the layout; the frame list shows the in-app/vendor split; a group with `degraded_reason` shows its explanation; a group with alerts lists them. Stop the server.

- [ ] **Step 6: Commit**

```bash
git add "web/app/groups/[id]/page.tsx"
git commit -m "feat(web): reorganise the group page into panels"
```

Message body:

```
Same content, given structure. The page carried nine unrelated blocks at
identical visual weight, so the fingerprint input and the frame breakdown -- the
two things that explain why an event landed where it did -- read as no more
important than the release string.

The back link goes: the sidebar is the navigation now.
```

---

## Task 7: The alerts page

**Files:**
- Modify: `web/app/alerts/page.tsx` (whole file)

**Interfaces:**
- Consumes: `Shell`, `Panel`, `AlertKindBadge`, `relativeTime`, `listAlerts`.
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Delete the local `KIND_STYLES` map**

Lines 9–25 of the current file. It is now `AlertKindBadge` in `app/components/ui/badge.tsx`, which Task 2 created with the same three entries, labels and icons.

- [ ] **Step 2: Rewrite the page body**

Keep `DELIVERY_NOTES`, the `listAlerts()` call, the try/catch, and both prose blocks verbatim — the intro about an alert being a row before it is an email, and the "Latency, honestly" section about detection only running when an event arrives. Those are the page's substance.

Structure:

```tsx
    <Shell current="alerts">
      <header className="mb-6">
        <h1 className="text-xl font-semibold tracking-tight text-ink-hi sm:text-2xl">
          Alerts
        </h1>
        <p className="mt-2 max-w-2xl text-sm leading-relaxed text-ink-low">
          {/* existing intro paragraph, unchanged */}
        </p>
      </header>

      <Panel className="mb-6 border-warn/25 bg-warn-bg/15">
        <h2 className="text-[10px] font-medium uppercase tracking-[0.09em] text-warn">
          Latency, honestly
        </h2>
        <p className="mt-2 text-sm leading-relaxed text-ink">
          {/* existing latency paragraph, unchanged */}
        </p>
      </Panel>

      {/* failure panel, empty state, or the list */}
    </Shell>
```

Each alert row becomes:

```tsx
      <li>
        <Link
          href={`/groups/${alert.group_id}`}
          className="flex items-start gap-3 rounded-xl border border-edge bg-surface-1 p-4 transition-colors hover:border-edge-strong hover:bg-surface-2"
        >
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <AlertKindBadge kind={alert.kind} />
              <span className="font-mono text-xs text-ink-low">{alert.service}</span>
              <h3 className="min-w-0 flex-1 truncate text-sm text-ink-hi">
                {alert.title}
              </h3>
            </div>
            {alert.detector && (
              <p className="mt-1.5 font-mono text-[11px] text-ink-low">
                {alert.detector} · {alert.observed} this hour against a baseline of{" "}
                {alert.baseline?.toFixed(2)} · score {alert.score?.toFixed(2)}
              </p>
            )}
            <p className="mt-1 text-[11px] text-ink-faint">
              {/* existing delivery note expression, unchanged */}
            </p>
          </div>
          <span className="shrink-0 text-[11px] text-ink-low">
            {relativeTime(alert.created_at)}
          </span>
        </Link>
      </li>
```

Delete the `← All groups` link and the "Detector scorecard" button; both are in the sidebar now.

- [ ] **Step 3: Verify no zinc classes survive**

```bash
cd web && grep -n "zinc" app/alerts/page.tsx || echo "no zinc left"
```

- [ ] **Step 4: Run the verification loop**

```bash
cd web && npm run lint && npm run build
```

- [ ] **Step 5: Commit**

```bash
git add web/app/alerts/page.tsx
git commit -m "feat(web): move the alerts page onto the shell"
```

Message body:

```
The page carried its own KIND_STYLES map, a third copy of the same chip pattern
that lived in format.ts twice over. It uses the shared badge now.

The latency section keeps its own tinted panel. It is the page's most important
paragraph -- an alert is not raised when a spike begins, and a spike inside a
quiet period is never seen at all -- and it should not read like body text.
```

---

## Task 8: The detectors and grouping pages

**Files:**
- Modify: `web/app/detectors/page.tsx`
- Modify: `web/app/how-grouping-works/page.tsx`

**Interfaces:**
- Consumes: `Shell`, `Panel`, `PanelHeader`, `StatTile`.
- Produces: nothing consumed elsewhere.

- [ ] **Step 1: Restyle `detectors/page.tsx`**

Keep `DESCRIPTIONS`, `ratio()`, the `getDetectorScorecard()` call, the try/catch, `anyJudged`, and all four prose blocks verbatim: the intro about three detectors judging every bucket, the "What these numbers are not" caveat, the empty states, and "Why this data breaks the textbook detectors". The caveat is the page's point and must not be softened or shortened.

Replace the `Metric` component with `StatTile` — it is the same thing with a hint line. Change `Metric`'s call sites so `hint` becomes part of the label area:

```tsx
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <StatTile label="Precision" value={ratio(row.tp, precisionDenominator)} />
          <StatTile label="Recall" value={ratio(row.tp, recallDenominator)} />
          <StatTile label="Fired" value={row.fired} />
          <StatTile label="Awaiting hindsight" value={row.pending} />
        </div>
        <p className="mt-3 font-mono text-[11px] text-ink-low">
          {row.tp} of {precisionDenominator} firings held up · {row.tp} of{" "}
          {recallDenominator} surges caught · {row.judged + row.pending} buckets judged
        </p>
```

Delete the now-unused `Metric` function. Wrap each detector in a `Panel`. The active/shadow chip becomes:

```tsx
          {row.is_active ? (
            <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[11px] font-medium text-accent-hi ring-1 ring-inset ring-accent/25">
              active
            </span>
          ) : (
            <span className="rounded-full bg-surface-2 px-2 py-0.5 text-[11px] text-ink-low">
              shadow
            </span>
          )}
```

Keep the caveat block visually distinct: `<Panel className="mb-6 border-warn/25 bg-warn-bg/15">` with its heading in `text-warn`.

Delete the `← All groups` link and wrap the page in `<Shell current="detectors">`.

- [ ] **Step 2: Restyle `how-grouping-works/page.tsx`**

Wrap in `<Shell current="grouping">`, delete the `← All groups` link, and constrain the prose:

```tsx
    <Shell current="grouping">
      <article className="max-w-2xl">
        {/* existing content */}
      </article>
    </Shell>
```

A dashboard column is far too wide for continuous prose; `max-w-2xl` puts it at a readable measure. Replace every `zinc` class with its token equivalent using the same mapping as Task 6, and give any code or pipeline diagram block `rounded-lg border border-edge bg-surface-0 p-3 font-mono text-[11px] text-ink`.

- [ ] **Step 3: Verify no zinc classes survive in either file**

```bash
cd web && grep -n "zinc" app/detectors/page.tsx app/how-grouping-works/page.tsx || echo "no zinc left"
```

- [ ] **Step 4: Run the verification loop**

```bash
cd web && npm run lint && npm run build
```

- [ ] **Step 5: Look at both pages**

```bash
cd web && npm run dev
```

Confirm the scorecard still shows precision, recall and the caveat, that exactly one detector is chipped `active`, and that the grouping page's prose sits at a readable width rather than spanning the full column. Stop the server.

- [ ] **Step 6: Commit**

```bash
git add web/app/detectors/page.tsx web/app/how-grouping-works/page.tsx
git commit -m "feat(web): move the detector and grouping pages onto the shell"
```

Message body:

```
The scorecard's Metric component was StatTile with a hint line, so it is StatTile
now and the hints collapse into one line under the row.

The grouping explainer was set at dashboard width, which is far too wide for
continuous prose. It gets a readable measure.

Both keep their caveats word for word. On the scorecard in particular, "this is
not accuracy" is the point of the page rather than a disclaimer on it.
```

---

## Task 9: Cleanup and full verification

**Files:**
- Modify: `web/lib/format.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: `relativeTime`, `formatBytes`, `DEGRADED_REASONS` survive unchanged.

- [ ] **Step 1: Confirm the old style helpers have no callers left**

```bash
cd web && grep -rn "levelStyle\|statusStyle\|LEVEL_STYLES\|STATUS_STYLES" app lib
```

Expected: matches only inside `lib/format.ts`. If any page still calls them, that page was missed — go back and finish it before deleting anything.

- [ ] **Step 2: Delete the dead exports from `web/lib/format.ts`**

Remove `LEVEL_STYLES`, `levelStyle`, `STATUS_STYLES` and `statusStyle` and their comments. Keep `DEGRADED_REASONS`, `formatBytes` and `relativeTime` exactly as they are — all three still have callers.

- [ ] **Step 3: Run the complete verification set**

From `web/`:

```bash
npm run lint
npm run build
grep -rn "use client" app lib || echo "zero client components"
grep -rn "zinc" app lib || echo "no zinc classes left anywhere"
```

From the repository root:

```bash
pattern='\bfetch[[:space:]]*\(|\baxios\b|node-fetch|\bundici\b|XMLHttpRequest|onrender\.com|render\.com'
git grep -InE "$pattern" -- web ':!web/package-lock.json' || echo "read path clean"
```

Expected: lint and build pass with no `DATABASE_URL`; all three greps print their reassurance lines.

- [ ] **Step 4: Walk every route at four widths**

```bash
cd web && npm run dev
```

At 1440, 1024, 768 and 390 pixels wide, visit `/`, a group page, `/alerts`, `/detectors` and `/how-grouping-works`. For each, confirm:
- the page body never scrolls horizontally;
- the sidebar is vertical at 1024 and above, and a horizontal bar below it;
- the active nav item is marked on every route;
- wide content — the trend chart, the fingerprint `<pre>`, the stack trace — scrolls inside its own container.

Stop the server.

- [ ] **Step 5: Commit**

```bash
git add web/lib/format.ts
git commit -m "refactor(web): drop the style helpers the badges replaced"
```

Message body:

```
levelStyle and statusStyle returned raw class strings and left the element to
every call site, which is how the same chip ended up written out on four pages.
Every caller is on the shared badge now, so they go.

relativeTime, formatBytes and DEGRADED_REASONS stay; all three still have
callers.
```

- [ ] **Step 6: Push and open the pull request**

```bash
git push -u origin redesign/dashboard
gh pr create --base main --title "Redesign the dashboard" --body "…"
```

The body should state what changed, that the Arial bug was the starting point, that the dashboard still ships zero client JavaScript, and that the build still passes without `DATABASE_URL`. Wait for CI and report the result.

---

## Self-review notes

**Spec coverage.** Tokens → Task 1. Fonts → Task 1. Shell, sidebar, nav counts → Task 3. Primitives → Task 2. Charts and contrast re-validation → Task 4. Overview with its four tiles and no-new-query aggregate → Tasks 4–5. Group detail → Task 6. Alerts → Task 7. Detectors and grouping → Task 8. Error handling → the failure `Panel` in Task 5 and the preserved try/catch in Tasks 6–8. Responsive rules → Task 3's breakpoints, checked in Tasks 5 and 9. `.gitignore` housekeeping → already committed with the spec.

**Known gap, stated rather than hidden.** There is no automated test for `summarise()` in `web/lib/overview.ts`, which is the only real logic this plan adds. A test would need a runner, and a runner is a new dependency the spec rules out. The function is a sum over fixed-length arrays and is checked in Task 5 Step 4 by confirming the tiles agree with the chart. If it ever grows past a sum, it should get a harness — and that would be the moment to reopen the dependency question.
