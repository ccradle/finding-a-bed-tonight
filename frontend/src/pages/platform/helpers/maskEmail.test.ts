import { describe, it, expect } from 'vitest';
import { maskEmail } from './maskEmail';

/**
 * Pure tests for the operator-email masking helper used by the
 * PlatformOperatorBanner.
 */

describe('maskEmail', () => {
  it('masks a typical email with first char preserved', () => {
    expect(maskEmail('ccradle@gmail.com')).toBe('c***@gmail.com');
  });

  it('masks longer addresses to the same 1-char + *** shape', () => {
    expect(maskEmail('verylongusername@example.org'))
      .toBe('v***@example.org');
  });

  it('handles single-char local part (degenerate but valid)', () => {
    expect(maskEmail('a@b.c')).toBe('a***@b.c');
  });

  it('returns empty string for null', () => {
    expect(maskEmail(null)).toBe('');
  });

  it('returns empty string for undefined', () => {
    expect(maskEmail(undefined)).toBe('');
  });

  it('returns empty string for empty string', () => {
    expect(maskEmail('')).toBe('');
  });

  it('returns input as-is when no @ sign present (defensive)', () => {
    expect(maskEmail('not-an-email')).toBe('not-an-email');
  });

  it('returns input as-is when @ is the first character (no local part)', () => {
    // RFC-invalid but defensive — we preserve the input rather than
    // produce nonsense like "***@domain".
    expect(maskEmail('@nowhere.com')).toBe('@nowhere.com');
  });

  it('handles addresses with multiple @ signs by anchoring on the first', () => {
    // e.g. user@first@second — uncommon but encountered in some
    // SMTP-edge-case test fixtures. Mask preserves everything after
    // the first @ as "domain."
    expect(maskEmail('user@first@second')).toBe('u***@first@second');
  });
});
