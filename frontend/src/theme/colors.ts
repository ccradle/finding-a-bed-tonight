/**
 * Color design tokens for Finding A Bed Tonight.
 *
 * These constants reference CSS custom properties defined in global.css.
 * Import and use in component inline styles:
 *
 *   import { color } from '../theme/colors';
 *   <div style={{ backgroundColor: color.bg, color: color.text }}>
 *   <a style={{ color: color.primaryText }}>Link</a>
 *   <button style={{ backgroundColor: color.primary, color: color.textInverse }}>Button</button>
 *
 * Dark mode architecture (Radix/Carbon split pattern):
 * - color.primary:     button fills, solid backgrounds — white text on it
 * - color.primaryText: links, labels, inline colored text — readable on bg
 * - color.primaryLight: active nav bg, selection — primaryText on it
 * In light mode these converge (same value). In dark mode they diverge.
 *
 * WCAG 2.1 AA contrast verified for all token pairs in both modes.
 * See global.css for specific ratios.
 *
 * For new components: ALWAYS use color.* tokens. Never hardcode hex values.
 */

export const color = {
  // Brand
  primary: 'var(--color-primary)',           // button fills, solid backgrounds
  primaryText: 'var(--color-primary-text)',   // links, labels, inline colored text
  primaryHover: 'var(--color-primary-hover)',
  primaryLight: 'var(--color-primary-light)', // active nav bg, selection bg
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
  textInverse: 'var(--color-text-inverse)',   // white — on colored button fills

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

  // Safety (DV shelters) — split like primary: dv for fills, dvText for readability
  dv: 'var(--color-dv)',
  dvText: 'var(--color-dv-text)',
  dvBg: 'var(--color-dv-bg)',
  dvBorder: 'var(--color-dv-border)',

  // Header (always dark bg + light text in both modes)
  headerBg: 'var(--color-header-bg)',
  headerText: 'var(--color-header-text)',
  headerGradientStart: 'var(--color-header-gradient-start)',
  headerGradientMid: 'var(--color-header-gradient-mid)',
  headerGradientEnd: 'var(--color-header-gradient-end)',
} as const;
