/**
 * Records the two clips in docs/media.
 *
 * Playwright is deliberately not a dependency of this repository. The dashboard's
 * package.json is a claim the README makes out loud -- four runtime packages, no test
 * runner -- and a screen recorder is a poor reason to stop that being true. So this
 * script resolves playwright from the working directory instead of from its own folder,
 * and is run from a scratch directory that has it installed:
 *
 *   mkdir /tmp/rec && cd /tmp/rec
 *   npm init -y && npm i -D playwright && npx playwright install chromium
 *   node /path/to/tools/media/record.mjs tour
 *   node /path/to/tools/media/record.mjs bet
 *
 * The webm files land in ./video. to-gif.sh turns them into the gifs the README shows.
 *
 * ## The second clip, and why it is not staged
 *
 * The bet is that the dashboard reads Postgres directly, so it renders in full while the
 * ingestion service is asleep. Showing that means showing a hundred-second wait, which no
 * short clip can hold in real time -- and faking the wait would make the clip an
 * illustration of a claim rather than evidence for it.
 *
 * So both halves of the second clip are real requests made while the camera runs. The
 * dashboard on the left is the deployed dashboard in an iframe. The counter on the right
 * is timing an actual request to the actual collector, sent no-cors because reading the
 * response is not the point and the elapsed time is. Nothing is simulated; the clip is
 * then sped up over the dead part, with the speed stated on screen and the counter still
 * reading true seconds. The compression is declared rather than hidden.
 */
import { createRequire } from "node:module";
import { mkdirSync, readdirSync, renameSync, statSync } from "node:fs";
import { join } from "node:path";

const require = createRequire(join(process.cwd(), "/"));
const { chromium } = require("playwright");

const DASHBOARD = "https://getstacklight.vercel.app";
const COLLECTOR = "https://stacklight.onrender.com/actuator/health";

const VIEWPORT = { width: 1280, height: 720 };
const OUT = join(process.cwd(), "video");

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * A caption strip along the bottom, in the dashboard's own tokens.
 *
 * Burned into the page rather than into the video afterwards: the text stays crisp at the
 * width the gif is scaled to, and a silent loop that nobody reads a README for has to
 * narrate itself.
 */
const CAPTION_CSS = `
  #cap {
    position: fixed; left: 0; right: 0; bottom: 0; z-index: 2147483647;
    display: flex; align-items: center; gap: 10px;
    padding: 14px 22px;
    background: linear-gradient(to top, rgba(9,9,11,0.97) 60%, rgba(9,9,11,0));
    font-family: ui-sans-serif, system-ui, sans-serif;
    font-size: 15px; line-height: 1.4; color: #ededf2;
    pointer-events: none;
  }
  #cap b { color: #9b7cff; font-weight: 600; }
  #cap .dot { width: 7px; height: 7px; border-radius: 999px; background: #7c5cff; flex: none; }
`;

async function caption(page, html) {
  await page.evaluate(
    ({ html, css }) => {
      if (!document.getElementById("cap-style")) {
        const s = document.createElement("style");
        s.id = "cap-style";
        s.textContent = css;
        document.head.append(s);
      }
      let el = document.getElementById("cap");
      if (!el) {
        el = document.createElement("div");
        el.id = "cap";
        document.body.append(el);
      }
      el.innerHTML = `<span class="dot"></span><span>${html}</span>`;
    },
    { html, css: CAPTION_CSS },
  );
}

async function record(name, scenario) {
  mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const context = await browser.newContext({
    viewport: VIEWPORT,
    recordVideo: { dir: OUT, size: VIEWPORT },
    colorScheme: "dark",
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();

  await scenario(page);

  await context.close();
  await browser.close();

  // Playwright names videos by an internal id; give it the name the README will use.
  // Only the files it named, and only the newest of those: an earlier version of this
  // picked whatever sorted last, which on the second run was the first clip -- so it
  // renamed the tour over the top of the recording that had just been made.
  const mine = readdirSync(OUT)
    .filter((f) => f.startsWith("page@") && f.endsWith(".webm"))
    .map((f) => ({ f, t: statSync(join(OUT, f)).mtimeMs }))
    .sort((a, b) => b.t - a.t);

  if (mine.length === 0) throw new Error("playwright wrote no video");
  renameSync(join(OUT, mine[0].f), join(OUT, `${name}.webm`));
  console.log(`wrote ${join(OUT, `${name}.webm`)}`);
}

/** A walk through what the thing is: faults, then why they grouped, then the detectors. */
async function tour(page) {
  await page.goto(DASHBOARD, { waitUntil: "networkidle" });
  await caption(
    page,
    "Errors grouped into distinct faults — and the banner says the traffic behind them was <b>written, not reported</b>",
  );
  await wait(4200);

  await page.evaluate(() => window.scrollTo({ top: 420, behavior: "smooth" }));
  await caption(page, "Filtering, searching and paging — all of it URL state, <b>no client JavaScript</b>");
  await wait(3800);

  const firstGroup = page.locator("main a[href^='/groups/']").first();
  await firstGroup.click();
  await page.waitForLoadState("networkidle");
  await caption(page, "A group page shows <b>why this event landed here</b>: the frames that decided it, and the input that was hashed");
  await wait(4600);

  await page.evaluate(() => window.scrollTo({ top: 700, behavior: "smooth" }));
  await caption(page, "In-app frames decide identity. Line numbers and vendor frames are excluded on purpose");
  await wait(4200);

  await page.goto(`${DASHBOARD}/detectors`, { waitUntil: "networkidle" });
  await caption(page, "Three detectors judged the same hours. One raises alerts, two run in shadow");
  await wait(4600);

  await page.evaluate(() => window.scrollTo({ top: 380, behavior: "smooth" }));
  await caption(page, "The scorecard chose between them — <b>ewma is active because it won</b>, not because it was argued for");
  await wait(4400);

  await page.goto(`${DASHBOARD}/alerts`, { waitUntil: "networkidle" });
  await caption(page, "An alert is a row before it is an email, so a failed send cannot lose one");
  await wait(4200);
}

/** The bet, with both halves happening for real while the camera runs. */
async function bet(page) {
  await page.setContent(
    `<!doctype html><html><head><meta charset="utf-8"><style>
      * { box-sizing: border-box; }
      body { margin:0; background:#09090b; color:#ededf2;
             font-family: ui-sans-serif, system-ui, sans-serif; height:100vh; overflow:hidden; }
      .row { display:flex; height: calc(100vh - 96px); }
      .pane { flex:1; display:flex; flex-direction:column; min-width:0; }
      .pane + .pane { border-left:1px solid #1f1f28; }
      .hd { padding:11px 16px; border-bottom:1px solid #1f1f28; display:flex; align-items:center; gap:9px;
            font-size:12px; letter-spacing:.09em; text-transform:uppercase; color:#7f7f8c; }
      .dot { width:7px; height:7px; border-radius:999px; flex:none; }
      .ok { background:#3dd68c; } .wait { background:#ffc46b; } .cold { background:#ff8080; }
      iframe { flex:1; border:0; width:100%; background:#09090b; }
      .body { flex:1; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:14px; }
      .t { font-size:64px; font-variant-numeric:tabular-nums; font-weight:600; letter-spacing:-.02em; }
      .sub { font-size:14px; color:#9a9aa6; text-align:center; max-width:340px; line-height:1.5; }
      .cap { height:96px; display:flex; align-items:center; gap:10px; padding:0 22px;
             border-top:1px solid #1f1f28; font-size:15px; line-height:1.4; }
      .cap b { color:#9b7cff; }
      .pill { margin-left:auto; font-size:12px; color:#7f7f8c; border:1px solid #2a2a36;
              border-radius:999px; padding:5px 11px; }
    </style></head><body>
      <div class="row">
        <div class="pane">
          <div class="hd"><span class="dot ok"></span>Dashboard — reads Postgres directly</div>
          <iframe id="dash"></iframe>
        </div>
        <div class="pane">
          <div class="hd"><span class="dot wait" id="d2"></span>Ingestion service — same moment</div>
          <div class="body">
            <div class="t" id="t">0.0 s</div>
            <div class="sub" id="s">Waiting for a reply from the collector.</div>
          </div>
        </div>
      </div>
      <div class="cap"><span id="c">Both panes are live requests, made now.</span>
        <span class="pill" id="p">real time</span></div>
    </body></html>`,
    { waitUntil: "domcontentloaded" },
  );

  await wait(1200);

  // Left: the deployed dashboard, timed by the browser like any visitor's would be.
  //
  // Timed by the frame's load event rather than by looking inside it. The dashboard is on
  // another origin, so its document is not readable from here -- which is the browser
  // behaving correctly and not something to work around. The load event fires across
  // origins and is the honest measure anyway: it is when the browser finished fetching
  // and rendering the frame, which is what a visitor waits for.
  await page.evaluate((url) => {
    const f = document.getElementById("dash");
    const started = performance.now();
    f.addEventListener("load", () => {
      window.__dashMs = performance.now() - started;
    });
    f.src = url;
  }, DASHBOARD);
  await page.waitForFunction(() => window.__dashMs !== undefined, null, { timeout: 60_000 });
  const dashMs = await page.evaluate(() => window.__dashMs);

  // And confirmed to have actually rendered, which Playwright can see even though the
  // page cannot: an empty frame that loaded fast would prove nothing.
  await page.frameLocator("#dash").locator("main").first().waitFor({ timeout: 30_000 });
  await page.evaluate(
    (ms) => {
      document.getElementById("c").innerHTML =
        `Dashboard answered in <b>${(ms / 1000).toFixed(2)} s</b>, fully rendered. Now the same question, to the service it does not need.`;
    },
    dashMs,
  );
  await wait(3000);

  // Right: a real request to the real collector, timed to the millisecond.
  await page.evaluate((url) => {
    const t = document.getElementById("t");
    const started = performance.now();
    window.__done = false;
    const tick = () => {
      const s = (performance.now() - started) / 1000;
      t.textContent = `${s.toFixed(1)} s`;
      if (!window.__done) requestAnimationFrame(tick);
    };
    tick();
    fetch(url, { mode: "no-cors", cache: "no-store" }).then(() => {
      window.__done = true;
      window.__elapsed = (performance.now() - started) / 1000;
      document.getElementById("d2").className = "dot ok";
      document.getElementById("s").textContent = "Awake. It had to start from cold.";
    });
  }, COLLECTOR);

  await page.evaluate(() => {
    document.getElementById("p").textContent = "sped up from here";
    document.getElementById("d2").className = "dot cold";
  });

  await page.waitForFunction(() => window.__done === true, null, { timeout: 300_000 });
  const elapsed = await page.evaluate(() => window.__elapsed);

  await page.evaluate(
    ({ cold, warm }) => {
      document.getElementById("p").textContent = "real time";
      document.getElementById("c").innerHTML =
        `<b>${cold.toFixed(0)} seconds</b> for the ingestion service to answer. The dashboard had already served everything in <b>${warm.toFixed(2)} s</b> — it never asks it anything.`;
    },
    { cold: elapsed, warm: dashMs / 1000 },
  );
  await wait(6000);
  console.log(`dashboard ${(dashMs / 1000).toFixed(2)} s, collector ${elapsed.toFixed(1)} s`);
}

const which = process.argv[2];
const scenarios = { tour, bet };
if (!scenarios[which]) {
  console.error(`usage: node record.mjs <${Object.keys(scenarios).join("|")}>`);
  process.exit(1);
}
await record(which, scenarios[which]);
