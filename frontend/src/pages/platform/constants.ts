/**
 * Compile-time constants for the platform-operator UI (F11 v0.54).
 *
 * Pin URLs / paths here so a future doc-tree move (e.g. moving the
 * user guide to a docs site) only needs one edit.
 */

/**
 * Canonical URL for the platform-operator user guide. Pinned to the
 * `main`-branch GitHub blob view so the link resolves whether the
 * operator is on the live deployment, the local dev stack, or
 * reading the doc on the GitHub UI.
 *
 * If the doc moves (rename / relocation / docs-site migration),
 * update this single constant — the in-UI help text on
 * /platform/mfa-enroll and /platform/dashboard will pick up the new
 * URL automatically.
 */
export const PLATFORM_OPERATOR_USER_GUIDE_URL =
  'https://github.com/ccradle/finding-a-bed-tonight/blob/main/docs/operations/platform-operator-user-guide.md';
