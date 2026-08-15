// Checks that web/package-lock.json still describes a tree that can be built.
//
// Running `npm install` or `npm audit fix` against this lockfile on a machine that
// does not need every platform's binaries leaves it incoherent. npm prunes optional
// entries it cannot use locally and does not touch the packages that depend on them,
// so the file ends up naming a dependency it no longer contains. It has been produced
// twice here, both times dropping @emnapi/core and @emnapi/runtime while three
// packages went on requiring them.
//
// Nothing about that is visible in the npm output -- the install reports success --
// and it is invisible in review unless somebody reads a lockfile diff closely enough
// to notice which entries left. So it is checked rather than remembered.
//
// Run it by hand with:  node .github/scripts/lockfile-guard.mjs

import { readFileSync } from "node:fs";

const LOCKFILE = "web/package-lock.json";

const lock = JSON.parse(readFileSync(LOCKFILE, "utf8"));
const packages = lock.packages ?? {};

const problems = [];

// ---------------------------------------------------------------------------
// The check that holds: every dependency named in the file resolves inside it.
//
// This is the one that catches the pruning, because it sees the consequence --
// a name with nothing behind it -- rather than any particular package.
//
// peerDependencies are excluded on purpose. They are the installing project's to
// satisfy and npm does not always place them. @napi-rs/wasm-runtime declares the
// @emnapi packages that way, but three others declare them under `dependencies`,
// so the pruning is caught regardless.
// ---------------------------------------------------------------------------

// npm resolves a dependency by walking up the node_modules levels above the
// package that asked for it, taking the first match.
function resolves(fromPath, name) {
  const levels = fromPath === "" ? [] : fromPath.split("/node_modules/");
  for (let depth = levels.length; depth >= 0; depth--) {
    const prefix = levels.slice(0, depth).join("/node_modules/");
    const candidate = `${prefix ? `${prefix}/` : ""}node_modules/${name}`;
    if (Object.hasOwn(packages, candidate)) return true;
  }
  return false;
}

const unresolved = new Map();

for (const [path, entry] of Object.entries(packages)) {
  if (entry.link) continue; // a workspace symlink, resolved through its target
  for (const field of ["dependencies", "optionalDependencies"]) {
    for (const name of Object.keys(entry[field] ?? {})) {
      if (resolves(path, name)) continue;
      const asked = unresolved.get(name) ?? [];
      asked.push(path.replace(/^node_modules\//, "") || "(the dashboard itself)");
      unresolved.set(name, asked);
    }
  }
}

if (unresolved.size > 0) {
  const lines = [...unresolved]
    .map(([name, askers]) => `  ${name}\n      required by ${askers.join(", ")}`)
    .join("\n");

  problems.push(
    `${LOCKFILE} names ${unresolved.size} package(s) it does not contain:\n\n${lines}\n\n` +
      "  This is what an `npm install` or `npm audit fix` run leaves behind on a\n" +
      "  machine that does not need these binaries: npm drops optional entries it\n" +
      "  cannot use locally and leaves the packages that depend on them in place. The\n" +
      "  install reports success. The file no longer describes a tree that installs on\n" +
      "  Linux, which is where this is built and deployed.\n\n" +
      "  Running npm again will not repair it -- it will prune them again. Either:\n\n" +
      "    git checkout HEAD -- web/package-lock.json\n\n" +
      "  if the rewrite was not the change you meant to make, or, if you did mean to\n" +
      "  change something in this file, restore it and re-apply your change as a hand\n" +
      "  edit so the rest of the file is left alone. README, `Local development`,\n" +
      "  says the same thing in prose."
  );
}

// ---------------------------------------------------------------------------
// The cheap check, and it is worth saying plainly that it would not have caught
// the failure above: through both rewrites the Linux count never moved, because
// @emnapi carries no platform in its name. It is here for the other shape of the
// same accident -- a file that kept the binaries of whoever ran npm and lost the
// ones the deployment needs.
//
// Derived rather than counted, so adding or dropping a dependency does not make it
// a number somebody has to remember to update.
// ---------------------------------------------------------------------------

const PLATFORM = /(darwin|win32|linuxmusl|linux|freebsd|android|wasm32)/;
const families = new Map();

for (const path of Object.keys(packages)) {
  const name = path.replace(/^.*node_modules\//, "");
  const found = name.match(PLATFORM);
  if (!found) continue;
  const family = name.slice(0, found.index).replace(/[-_]$/, "");
  const platforms = families.get(family) ?? new Set();
  platforms.add(found[1] === "linuxmusl" ? "linux" : found[1]);
  families.set(family, platforms);
}

const missingLinux = [...families]
  .filter(([, p]) => (p.has("darwin") || p.has("win32")) && !p.has("linux"))
  .map(([family, p]) => `  ${family} — has ${[...p].sort().join(", ")}, has no linux build`);

if (missingLinux.length > 0) {
  problems.push(
    `${LOCKFILE} carries other platforms' binaries but not Linux's:\n\n` +
      `${missingLinux.join("\n")}\n\n` +
      "  Whatever wrote this file kept the binaries of the machine it ran on. CI and\n" +
      "  the deployment both build on Linux, so `npm ci` cannot install what is missing.\n" +
      "  Restore the file and re-apply the change by hand rather than letting npm\n" +
      "  regenerate it."
  );
}

// ---------------------------------------------------------------------------

if (problems.length > 0) {
  for (const problem of problems) {
    console.error(`::error::${problem.split("\n")[0]}`);
    console.error(`\n${problem}\n`);
  }
  process.exit(1);
}

const entries = Object.keys(packages).length;
const coverage = [...families]
  .sort()
  .map(([family, p]) => `${family} (${[...p].sort().join("/")})`)
  .join(", ");

console.log(`lockfile coherent: ${entries} entries, every named dependency present`);
console.log(`native binaries cover linux for all ${families.size}: ${coverage}`);
