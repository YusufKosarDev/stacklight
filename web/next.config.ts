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
 */
const CONTENT_SECURITY_POLICY = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline'",
  "style-src 'self' 'unsafe-inline'",
  // next/font copies the font files into the build, so there is no font host to allow.
  "font-src 'self'",
  "img-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

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
