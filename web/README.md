# web

The dashboard. Next.js 16 App Router on React 19, deployed to Vercel.

It reads Postgres directly and never speaks to the ingestion service. That is
the architectural bet the rest of the project is built on: the collector runs on
an instance that sleeps and takes about a hundred seconds to wake, and a
dashboard proxied through it would be unusable for that whole window. Two checks
guard the claim — a grep in CI, and a render test that watches the socket and
fails if any page dials anything at all.

```bash
npm ci
npm test               # unit suite, then the render suite
npm run dev
```

**No test runner is installed.** The dependency list is part of what this is, and
a runner would have been the first thing in it. `node --test` runs the unit suite
against TypeScript directly; the render suite compiles the pages with the
TypeScript already here and renders them with the `react-dom` the application
already depends on. Two constraints come with that and the
[main README](../README.md#local-development) states them.

**No client components.** `grep -rn "use client" .` returns nothing, and every
interactive thing on these pages is a link, a `<form method="get">` or a CSS
hover. Next.js still ships its own runtime; what this adds to it is nothing.

`npm run build` succeeds without `DATABASE_URL` on purpose — the dashboard must
never reach the database at build time, and CI enforces it.

> **`npm install` and `npm audit fix` are not safe here.** They rewrite
> `package-lock.json` from the machine they run on and quietly drop optional
> entries that packages still depend on, producing a tree that cannot be
> installed on Linux — while reporting success. A dependency change is a hand
> edit followed by `npm ci`. The reasoning, and the guard that catches it, are in
> [the main README](../README.md#the-lockfile-cannot-be-regenerated-and-npm-will-not-say-so).

| | |
|---|---|
| `app/` | the routes, and the components they are built from |
| `lib/` | the Neon handle, the read queries, the list's URL state |
| `test/` | the unit suite, and `render/` for the pages |
| `next.config.ts` | response headers, including a CSP that says which line of it is weak |
