/**
 * Color design tokens for Finding A Bed Tonight.
 *
 * These constants reference CSS custom properties defined in global.css.
 * Import and use in component inline styles:
 *
 *   import { color } from '../theme/colors';
 *   <div style={{ backgroundColor: color.bg, color: color.text, border: `1px solid ${color.border}` }}>
 *
 * The CSS custom properties are the canonical source of truth. Light and dark
 * mode values are defined in global.css via @media (prefers-color-scheme: dark).
 * These TypeScript constants provide type safety and IDE autocomplete.
 *
 * WCAG 2.1 AA contrast ratios verified for all token pairs:
 * - Normal text on bg: 4.5:1 minimum in both light and dark modes
 * - Large text on bg: 3:1 minimum
 * - UI components: 3:1 against adjacent colors
 *
 * For new components: ALWAYS use color.* tokens. Never hardcode hex values.
 * See the color audit in openspec/changes/color-system-dark-mode/design.md D2.
 */

/** Brand colors */
export const color = {
  // Brand
  primary: 'var(--color-primary)',
  primaryHover: 'var(--color-primary-hover)',
  primaryLight: 'var(--color-primary-light)',
  primaryDisabled: 'var(--color-primary-disabled)',

  // Surface (backgrounds)
  bg: 'var(--color-bg)',
  bgSecondary: 'var(--color-bg-secondary)',
  bgTertiary: 'var(--color-bg-tertiary)',
  bgHighlight: 'var(--color-bg-highlight)',

  // Text
  text: 'var(--color-text)',
  textSecondary: 'var(--color-text-secondary)',
  textTertiary: 'var(--color-text-tertiary)',
  textMuted: 'var(--color-text-muted)',
  textInverse: 'var(--color-text-inverse)',

  // Border
  border: 'var(--color-border)',
  borderLight: 'var(--color-border-light)',
  borderMedium: 'var(--color-border-medium)',
  borderFocus: 'var(--color-border-focus)',

  // Status: Success
  success: 'var(--color-success)',
  successBg: 'var(--color-success-bg)',
  successBorder: 'var(--color-success-border)',
  successBright: 'var(--color-success-bright)',

  // Status: Error
  error: 'var(--color-error)',
  errorBg: 'var(--color-error-bg)',
  errorBorder: 'var(--color-error-border)',
  errorMid: 'var(--color-error-mid)',

  // Status: Warning / Aging
  warning: 'var(--color-warning)',
  warningBg: 'var(--color-warning-bg)',
  warningMid: 'var(--color-warning-mid)',
  warningBright: 'var(--color-warning-bright)',

  // Safety (DV shelters)
  dv: 'var(--color-dv)',
  dvBg: 'var(--color-dv-bg)',
  dvBorder: 'var(--color-dv-border)',

  // Header
  headerBg: 'var(--color-header-bg)',
  headerGradientStart: 'var(--color-header-gradient-start)',
  headerGradientMid: 'var(--color-header-gradient-mid)',
  headerGradientEnd: 'var(--color-header-gradient-end)',
} as const;
