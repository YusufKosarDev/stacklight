# Dashboard redesign — design

**Date:** 2026-08-10
**Status:** approved, ready for implementation planning

---

## Why

The dashboard is the only part of Stacklight anyone sees. It currently reads as an
unstyled scaffold: every element carries the same visual weight, the type scale runs from
`text-2xl` straight to `text-xs` with nothing in between, and there is no accent colour
outside the charts.

Part of that is a bug rather than a choice. `globals.css:25` sets
`font-family: Arial, Helvetica, sans-serif` on `body`. `layout.tsx` loads Geist Sans and
Geist Mono and puts their variables on `<html>`, but nothing applies them — **the whole
dashboard renders in Arial.** The same file still carries Next.js scaffold leftovers: a
`--background: #ffffff` light default and a `prefers-color-scheme` block, both dead since
`body` is hard-coded `bg-zinc-950`.

The goal is a dashboard that looks deliberately designed within two seconds of loading,
without becoming a marketing page. The content is error triage; the design has to stay a
tool.

## What is being built

A restructured application shell with a persistent sidebar, and a redesigned overview page
built from stat tiles, an aggregate trend and a group list. All five routes move onto the
shell. The visual character is "product" — deep neutral surfaces with a violet accent, a
soft radial glow, rounded panels and pill badges.

### Non-goals

- No new runtime dependencies. No shadcn/ui, no Radix, no chart library. Hand-written
  Tailwind, consistent with how the dashboard is built today.
- No change to what the dashboard can *do*. Still read-only; status changes still go
  through the ingestion service.
- No client-side data fetching. This is load-bearing, not stylistic — see below.
- **No client components.** The dashboard currently has none: `grep -rn "use client" web/`
  returns nothing, so it ships zero JavaScript of its own. That is worth keeping, and the
  redesign does not need to spend it.

---

## Hard constraints

These are existing guarantees that the redesign must not break.

**The read path never leaves Postgres.** The dashboard renders while the ingestion service
is asleep, which is the project's central architectural claim. CI enforces it: the `policy`
job greps `web/` for `fetch(`, `axios`, `undici`, `onrender.com` and fails on a match. Every
new component is a server component reading through `lib/queries.ts`, except where noted.

**`npm run build` must succeed with no `DATABASE_URL`.** CI builds without it. This is why
the shell cannot naively query in the root layout: an async layout that reads the database
would make Next try to prerender `/how-grouping-works` at build time and fail.

**Resolution:** the root layout declares `export const dynamic = "force-dynamic"`, which
applies to every segment beneath it. Nothing prerenders, so the build never reaches the
database. The cost is that `/how-grouping-works` stops being a static file and becomes a
per-request render — acceptable for one explainer page, and stated here rather than
discovered later.

---

## Design tokens

Replaces the scaffold in `web/app/globals.css`. Defined as CSS custom properties and
exposed to Tailwind through `@theme`, so components use utility classes rather than inline
hex values.

| Role | Value | Used for |
|---|---|---|
| `surface-0` | `#09090B` | page background, under a radial violet glow |
| `surface-1` | `rgba(255,255,255,.025)` | panels, rows, tiles |
| `surface-2` | `rgba(255,255,255,.045)` | row hover |
| `border` | `#1F1F28` | panel and row borders |
| `text-hi` | `#EDEDF2` | headings, numbers |
| `text-mid` | `#C9C9D2` | body |
| `text-low` | `#7A7A88` | labels, metadata |
| `text-faint` | `#57575F` | axis ticks |
| `accent` / `accent-hi` / `accent-lo` | `#7C5CFF` / `#9B7CFF` / `#6344E8` | active nav, chart series, meters |
| `danger` | `#FF8080` on `rgba(255,90,90,.13)` | error level, regressed state |
| `warn` | `#FFC46B` on `rgba(255,180,60,.13)` | warn level |
| `ok` | `#3DD68C` | read-path health dot |

**Fonts.** Geist Sans becomes the body font and Geist Mono is used for identifiers —
fingerprints, culprits, frame signatures, timestamps. The Arial rule is deleted; `body`
gets `font-family: var(--font-geist-sans)`.

**Chart colours must be re-validated.** `charts.tsx` currently documents its blue as
"the validated dark-mode blue; it clears 3:1 against the page surface". The violet
replacements have to clear the same bar against `#09090B`, and the comment must be updated
to say what was actually checked rather than inherited.

---

## Components

### Shell

- **`app/components/shell/shell.tsx`** — server component taking `current` and the page
  body. Renders sidebar plus main column.
- **`app/components/shell/sidebar.tsx`** — server component. Logo mark, nav, footer status
  block. Renders the read-path line: a green dot, the measured query time for the current
  render, and "ingestion asleep — page unaffected". This puts the architectural claim on
  every page permanently.
- **`app/components/shell/nav-counts.tsx`** — async server component fetching the sidebar
  badge numbers, wrapped in its own try/catch. A database failure renders nav without
  counts rather than taking the page down.

**Active-link state without a client component.** The obvious implementation is
`usePathname()`, which would make this the dashboard's first client component. Instead each
page renders the shell itself and names its own section:
`<Shell current="groups">…</Shell>`. A page always knows which page it is; asking the
browser to work it out would be spending JavaScript to recover something already known at
render time. The cost is one prop per route, five times.

`app/layout.tsx` keeps `<html>`/`<body>`, the fonts, and declares `force-dynamic` for every
segment beneath it.

### Primitives

Small, single-purpose, in `app/components/ui/`:

- **`panel.tsx`** — the bordered rounded surface used by every card, with an optional
  header row (title left, secondary stat right).
- **`stat-tile.tsx`** — label, big tabular number, optional unit and optional meter bar.
- **`badge.tsx`** — level and status chips. `levelStyle` and `statusStyle` in
  `lib/format.ts` are **deleted** along with their `LEVEL_STYLES` / `STATUS_STYLES` maps:
  they return raw class strings and leave every call site to assemble the element by hand,
  which is why the same chip markup is currently repeated on four pages. `relativeTime`,
  `formatBytes` and `DEGRADED_REASONS` stay as they are.

### Charts

`app/components/charts.tsx` keeps `Sparkline` and `TrendChart`, restyled to the tokens, and
gains:

- **`OverviewTrend`** — the 24-hour aggregate bar chart on the overview page.

**This needs no new query.** `listSparklines()` already returns every group's 24 hourly
counts; summing them by hour index gives the aggregate. Stated explicitly because the
obvious implementation is a new `SELECT`, and this page is on the critical read path.

---

## Pages

### `/` — Overview

Replaces the current header + read-path card + storage card + list.

1. Page title and subtitle.
2. Four stat tiles: **open faults**, **events (24h)**, **regressed**, **storage** with a
   meter against the 512 MB plan limit.
3. Aggregate 24-hour trend panel.
4. Group list. Each row: level or status badge, title, mono metadata line
   (culprit · service · platform), 24-hour sparkline, total count, relative last-seen.
   **Regressed rows are tinted red** — of the four states it is the one that should shout.

All four tile values derive from data the page already fetches: open and regressed counts
from `listGroups()`, events-24h from the summed sparklines, storage from
`getStorageStatus()`.

### `/groups/[id]` — Detail

Same content, reorganised into panels: header block with badges and title, a stat row
(events, first seen, last seen, releases), the trend with its range switcher, then
fingerprint input, frame breakdown, similar groups and recent alerts as separate panels.
The range switcher already works as `?range=` links and stays that way.

### `/alerts`, `/detectors`, `/how-grouping-works`

Restyled onto the shell and the new primitives. `/detectors` keeps its precision/recall
cards and the "this is agreement with a rule, not accuracy" caveat. `/how-grouping-works`
gets a readable prose measure rather than the full-width dashboard column.

---

## Error handling

The existing pattern on `/` — catch, log server-side, return `{ ok: false }` — is kept and
extended to every page. Two rules carry over unchanged:

- The driver error can carry the host and role from the connection string, so it stays in
  the server log and never reaches the page.
- The failure state gets designed rather than left as a red box: a panel that says the
  query failed and that details are in the server log.

Sidebar counts fail independently, as described above.

---

## Responsive behaviour

The sidebar cannot simply vanish on small screens.

- **≥ 1024px:** persistent sidebar, main column beside it.
- **< 1024px:** sidebar collapses to a horizontal bar at the top of the page — same links,
  same counts, no drawer and no JavaScript. A drawer would mean state, and the nav is four
  items.
- Stat tiles reflow 4 → 2 → 1. The trend and any wide content scroll inside their own
  container; the page body never scrolls horizontally.

---

## Verification

| Check | How |
|---|---|
| Build guarantee | `npm run build` with no `DATABASE_URL` — must still pass |
| Lint | `npm run lint` |
| Read-path policy | the CI `policy` job's grep must stay clean |
| Fonts actually applied | computed `font-family` is Geist, not Arial |
| Still zero client JS | `grep -rn "use client" web/app web/lib` stays empty |
| Real data | `npm run dev` against the live `DATABASE_URL`, every route walked |
| Responsive | 1440 / 1024 / 768 / 390 widths, no horizontal body scroll |
| Contrast | chart series and text tokens checked against `#09090B`, comment updated with what was measured |

The dashboard has no test suite today and this redesign does not add one — the meaningful
checks here are the build guarantee, the policy grep and looking at the rendered pages.
That is a stated limit, not an oversight.

---

## Housekeeping

`.superpowers/` is added to `.gitignore`; the brainstorming mockups live there and should
not enter the repository.
