/**
 * Typography design tokens for Finding A Bed Tonight.
 *
 * These constants reference CSS custom properties defined in global.css.
 * Import and use in component inline styles:
 *
 *   import { font, text, weight, leading } from '../theme/typography';
 *   <div style={{ fontSize: text.base, fontWeight: weight.bold, lineHeight: leading.normal }}>
 *
 * The CSS custom properties are the canonical source of truth.
 * These TypeScript constants provide type safety and IDE autocomplete.
 */

/** Font family stacks */
export const font = {
  sans: 'var(--font-sans)',
  mono: 'var(--font-mono)',
} as const;

/** Font sizes — named scale matching global.css */
export const text = {
  '2xs': 'var(--text-2xs)',   // 11px — badge labels, tiny hints
  xs: 'var(--text-xs)',       // 12px — badges, small labels, hints
  sm: 'var(--text-sm)',       // 13px — input labels, table cells
  base: 'var(--text-base)',   // 14px — body text, buttons, form labels
  md: 'var(--text-md)',       // 16px — form inputs, emphasized text
  lg: 'var(--text-lg)',       // 18px — header title, sub-headings
  xl: 'var(--text-xl)',       // 20px — section headings
  '2xl': 'var(--text-2xl)',   // 24px — page headings (h1)
  '3xl': 'var(--text-3xl)',   // 28px — large metric values
  '4xl': 'var(--text-4xl)',   // 48px — emoji placeholders
} as const;

/** Font weights */
export const weight = {
  normal: 'var(--font-normal)',       // 400
  medium: 'var(--font-medium)',       // 500
  semibold: 'var(--font-semibold)',   // 600
  bold: 'var(--font-bold)',           // 700
  extrabold: 'var(--font-extrabold)', // 800
} as const;

/** Line heights — unitless ratios (WCAG 1.4.12 compliant) */
export const leading = {
  tight: 'var(--leading-tight)',     // 1.25 — headings
  normal: 'var(--leading-normal)',   // 1.5 — body text (WCAG minimum)
  relaxed: 'var(--leading-relaxed)', // 1.75 — long-form text
} as const;
