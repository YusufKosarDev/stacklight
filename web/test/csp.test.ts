/**
 * The one hole in the content policy, and where it is allowed to be.
 *
 * Preview deployments allow Vercel's comment toolbar to load; production must not,
 * and neither must a copy running anywhere else. That is a one-line condition in
 * `next.config.ts` and exactly the kind of line that gets loosened during a debugging
 * session and left loose -- `!== "production"` instead of `=== "preview"` reads the
 * same at a glance and opens the hole on every machine that is not a production
 * build.
 *
 * So the config is loaded under each environment and asked what it would send. It is
 * plain data with no runtime imports -- the `NextConfig` type is erased -- so this
 * costs a module load and no runner.
 *
 * The cache-busting query is what makes three loads three answers: the condition runs
 * once at module scope, which is the point (it is baked into the build rather than
 * decided per request), and a second plain `import` would hand back the first result.
 */
import test from "node:test";
import assert from "node:assert/strict";

/** The policy `next.config.ts` would emit with VERCEL_ENV set to `value`. */
async function policyFor(value: string | undefined, cacheBust: number): Promise<string> {
  const before = process.env.VERCEL_ENV;
  if (value === undefined) delete process.env.VERCEL_ENV;
  else process.env.VERCEL_ENV = value;

  try {
    const loaded = await import(`../next.config.ts?csp=${cacheBust}`);
    const config = loaded.default;
    const rules = await config.headers();
    const header = rules[0].headers.find(
      (entry: { key: string }) => entry.key === "Content-Security-Policy",
    );
    return header.value as string;
  } finally {
    if (before === undefined) delete process.env.VERCEL_ENV;
    else process.env.VERCEL_ENV = before;
  }
}

test("production refuses the preview toolbar", async () => {
  const policy = await policyFor("production", 1);

  assert.doesNotMatch(policy, /vercel\.live/);
  assert.doesNotMatch(policy, /pusher/);
  assert.doesNotMatch(policy, /frame-src/);
  // And the directives that carry the weight are still there and still closed.
  assert.match(policy, /default-src 'self'/);
  assert.match(policy, /object-src 'none'/);
  assert.match(policy, /frame-ancestors 'none'/);
  assert.match(policy, /base-uri 'self'/);
});

test("anywhere that is not a preview gets the production policy", async () => {
  // A local `next start` and a `vercel dev` both land here. Neither has a toolbar to
  // serve, and the policy somebody tests against locally should be the one that
  // ships -- which a `!== "production"` condition would quietly break.
  const policy = await policyFor(undefined, 2);

  assert.doesNotMatch(policy, /vercel\.live/);
});

test("a preview allows the toolbar, and only what the toolbar needs", async () => {
  const policy = await policyFor("preview", 3);

  const directives = new Map(
    policy.split("; ").map((part) => {
      const [name, ...sources] = part.split(" ");
      return [name, sources];
    }),
  );

  assert.ok(directives.get("script-src")?.includes("https://vercel.live"));
  assert.ok(directives.get("connect-src")?.includes("wss://ws-us3.pusher.com"));
  assert.ok(directives.get("frame-src")?.includes("https://vercel.live"));
  assert.ok(directives.get("font-src")?.includes("https://assets.vercel.com"));

  // The hole is one vendor's toolbar, not a general opening: nothing became a
  // wildcard, and the page still cannot be framed by anybody.
  assert.doesNotMatch(policy, /\*/);
  assert.deepEqual(directives.get("frame-ancestors"), ["'none'"]);
  assert.deepEqual(directives.get("object-src"), ["'none'"]);
  assert.deepEqual(directives.get("base-uri"), ["'self'"]);
  assert.deepEqual(directives.get("form-action"), ["'self'"]);
});
