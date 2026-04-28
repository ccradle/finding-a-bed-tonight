/**
 * Mask an operator email for display in the persistent banner.
 *
 * Per operator decision 9.2 (warroom round 1): banner displays
 * `c***@gmail.com` rather than the full email, defending against
 * shoulder-surfing + screenshot leakage. The operator can still
 * verify which account they're logged into (the first character +
 * domain are preserved).
 *
 * Format:
 *   "ccradle@gmail.com" → "c***@gmail.com"
 *   "x@y.z"             → "x***@y.z" (single-char local part — degenerate
 *                          but preserves the contract that local part is
 *                          always shown as 1 char + ***)
 *   ""                  → ""
 *   "no-at-sign"        → "no-at-sign" (return as-is; no @ to anchor on)
 *   null/undefined      → ""
 *
 * Pure function, no DOM access, fully testable.
 */
export function maskEmail(email: string | null | undefined): string {
  if (!email) return '';
  const at = email.indexOf('@');
  if (at <= 0) return email;
  const first = email.charAt(0);
  const domain = email.slice(at);
  return `${first}***${domain}`;
}
