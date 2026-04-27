/// <reference types="vite/client" />

/**
 * Type declarations for FABT-specific Vite env vars.
 *
 * Add new VITE_* keys here so callers get strict typing on
 * `import.meta.env.VITE_FOO`. Vite reads these from `.env*` files at
 * build time and substitutes them as string literals into the bundle.
 *
 * NOTE: Vite env vars are ALWAYS strings (or undefined). Compare with
 * `=== 'true'` rather than truthy-checking the raw value.
 */
interface ImportMetaEnv {
  /**
   * Build-time gate for the F11 platform-operator UI (`/platform/*` routes).
   *
   * - `'true'` (default in dev + prod via Dockerfile.frontend ARG): the
   *   platform chunk is dynamically imported on demand; routes resolve.
   * - `'false'`: the lazy import literal is dead-code-eliminated by
   *   Rollup (a top-level `if (... !== 'true')` branch is the eliminator),
   *   `dist/assets/PlatformPlaceholder-*.js` is absent from the build,
   *   and `/platform/*` routes fall through to the standard NotFound.
   * - `undefined`: same effect as `'false'` (the comparison is `=== 'true'`,
   *   so any non-'true' value disables the routes). Type permits undefined
   *   so callers don't dereference unsafely.
   *
   * This is the rollback lever for v0.54: rebuild with
   * `VITE_PLATFORM_UI_ENABLED=false npm run build` + redeploy
   * frontend container to remove the SPA layer without touching backend
   * or DB. ~6 minute RTO. See OpenSpec change `platform-operator-ui`
   * design.md Decision D7.
   */
  readonly VITE_PLATFORM_UI_ENABLED: string | undefined;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
