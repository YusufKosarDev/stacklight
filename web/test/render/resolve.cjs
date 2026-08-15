/**
 * Teaches Node the two specifiers the compiled pages still contain.
 *
 * `tsc` rewrites types and JSX but leaves import specifiers exactly as written, so the
 * emitted pages still ask for `@/lib/queries` and `@/app/components/ui/panel`. That
 * alias is a bundler convention Node has never heard of, so without this the compiled
 * output cannot be loaded at all.
 *
 * The second job is the data. Every page reaches the database through `@/lib/queries`
 * and through nothing else, so pointing that one specifier at the fixtures is enough to
 * render any page with known rows. It is the seam the pages already have rather than one
 * cut for testing: `lib/db.ts` builds its handle lazily inside `sql()`, so importing the
 * real module opens no connection and swapping it raises no question about whether the
 * read path still goes where it says it does.
 *
 * ## Why this patches a private function
 *
 * The emit is CommonJS -- `module: nodenext` with no `"type": "module"` above it -- so
 * the ESM resolve hook does not apply and `Module._resolveFilename` is where CommonJS
 * decides what a specifier means. It is the same hook the mainstream path-alias loaders
 * use, and the alternative was worse: emitting ESM would need every relative import in
 * `app/` to carry a `.js` extension, which is a change to the application to suit its
 * tests.
 *
 * Loaded with `node --require ./test/render/resolve.cjs`.
 */
const Module = require("node:module");
const path = require("node:path");

const OUT = path.resolve(__dirname, "../../.render-out");
const FIXTURES = path.join(OUT, "test/render/fixtures.js");

const inherited = Module._resolveFilename;

Module._resolveFilename = function (request, ...rest) {
  if (request === "@/lib/queries") {
    return FIXTURES;
  }
  if (request.startsWith("@/")) {
    return inherited.call(this, path.join(OUT, request.slice(2)), ...rest);
  }
  return inherited.call(this, request, ...rest);
};
