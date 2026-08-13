import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Pages compiled so the render tests can load them. Machine output, and linting it
    // reports on the emitter rather than on anything anybody wrote.
    ".render-out/**",
  ]),
  {
    // The render tests' module resolver has to be CommonJS. It runs as a `--require`
    // preload and hooks the resolver CommonJS itself uses, so `require` is not a style
    // choice here -- an ESM version of this file could not do the job at all.
    files: ["test/render/resolve.cjs"],
    rules: { "@typescript-eslint/no-require-imports": "off" },
  },
]);

export default eslintConfig;
