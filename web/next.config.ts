import type { NextConfig } from "next";

/**
 * Response headers.
 *
 * This dashboard is a poor target: it has no login, sets no cookie, holds no token in
 * the browser and offers no way to write anything. There is nothing here to steal a
 * session for. What it does do is render text that arrived from somewhere else --
 * exception messages and stack traces, posted by whoever holds an ingest key -- and
 * that is the one thing worth building a second line around.
 *
 * The first line is React, which escapes what it interpolates; there is no
 * `dangerouslySetInnerHTML` anywhere in `app/`, and the triage console on the other
 * service has a test asserting the same thing about `innerHTML`. These headers are the
 * second line, for the day the first one is got around.
 *
 * ## The script policy is the weak part, and it is weak on purpose
 *
 * `script-src` carries `'unsafe-inline'`. Next.js emits one inline bootstrap script per
 * page to hand the server-rendered payload to React, and without that keyword the page
 * loads and the console fills with violations.
 *
 * The strong version is a nonce: middleware generates one per request, the header names
 * it, and the inline script carries it. It is not taken here because this application
 * has no middleware at all, and adding one that runs on every request -- to a read path
 * whose whole argument is that it does as little as possible between the browser and
 * Postgres -- is a real cost against a threat this page does not have. It is the right
 * change the day this dashboard grows a login.
 *
 * So `script-src` stops an injected `<script src>` from loading and does not stop an
 * injected inline one. Said plainly rather than left for a reader to work out from the
 * keyword, because a policy nobody understands is a policy nobody maintains.
 *
 * Everything else is closed: no plugins, no framing, no base-tag rewriting, forms can
 * only post here, and there is no host in any directive but this one -- which is the
 * same claim the CI `policy` job makes about the source, now made to the browser.
 *
 * `style-src` needs `'unsafe-inline'` for a duller reason: the charts size their bars
 * with `style={{ height }}`, since the values come from the data rather than from a
 * stylesheet.
 *
 * Vercel already sends `Strict-Transport-Security`, so it is not repeated here.
 *
 * One deliberate exception to all of the above is made for preview deployments, and
 * it is described where it is made.
 */
const directives: Record<string, string[]> = {
  "default-src": ["'self'"],
  "script-src": ["'self'", "'unsafe-inline'"],
  "style-src": ["'self'", "'unsafe-inline'"],
  // next/font copies the font files into the build, so there is no font host to allow.
  "font-src": ["'self'"],
  "img-src": ["'self'", "data:"],
  "connect-src": ["'self'"],
  "object-src": ["'none'"],
  "base-uri": ["'self'"],
  "form-action": ["'self'"],
  "frame-ancestors": ["'none'"],
};

/**
 * Preview deployments get one hole in that, and only preview deployments.
 *
 * Vercel injects its comment toolbar into a preview by adding a script from
 * vercel.live, which the policy above refuses -- correctly, and with the console
 * error to prove it. The toolbar is how somebody leaves a note on a change before
 * it ships, so refusing it costs a real thing on the one deployment where that
 * thing is wanted.
 *
 * The hosts are Vercel's own published list rather than a set worked out from
 * error messages, so the next piece of the toolbar to load does not need another
 * round of this.
 *
 * **The condition is `=== "preview"`, not `!== "production"`.** Those differ in two
 * places and both matter: a local `next start` and a `vercel dev` would both take
 * the loose branch under the looser test, and neither has a toolbar to serve. This
 * way the policy anybody runs locally is the policy production sends, which is the
 * only way local testing of it means anything.
 *
 * This is read once, when `headers()` is evaluated at build time. Each deployment
 * builds with its own `VERCEL_ENV`, so the production build never emits these hosts
 * -- it is not a runtime check that could be got at.
 */
if (process.env.VERCEL_ENV === "preview") {
  directives["script-src"].push("https://vercel.live");
  directives["connect-src"].push("https://vercel.live", "wss://ws-us3.pusher.com");
  directives["img-src"].push("https://vercel.live", "https://vercel.com", "blob:");
  directives["style-src"].push("https://vercel.live");
  directives["font-src"].push("https://vercel.live", "https://assets.vercel.com");
  // Absent otherwise, so `default-src 'self'` keeps refusing every foreign frame.
  directives["frame-src"] = ["'self'", "https://vercel.live"];
}

const CONTENT_SECURITY_POLICY = Object.entries(directives)
  .map(([directive, sources]) => `${directive} ${sources.join(" ")}`)
  .join("; ");

const nextConfig: NextConfig = {
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Content-Security-Policy", value: CONTENT_SECURITY_POLICY },
          // Redundant beside frame-ancestors and kept for the browsers that only
          // understand this one.
          { key: "X-Frame-Options", value: "DENY" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          // Nothing here asks for a device, so nothing here should be able to.
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
          },
        ],
      },
    ];
  },
};

export default nextConfig;
