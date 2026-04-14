import { describe, it, expect } from 'vitest';
import { isActive, statusLabelId, statusBadgeColors } from './myPastHoldsHelpers';
import { color } from '../theme/colors';

/**
 * Unit tests for the pure helpers powering MyPastHoldsPage's row render.
 * Extracted + covered per Phase 3 session 1 war-room M-1 — catches the
 * class of "status typo in one branch" regressions that a Playwright
 * rendering test wouldn't cleanly attribute.
 */

describe('isActive', () => {
  it('treats HELD as active', () => {
    expect(isActive('HELD')).toBe(true);
  });

  it('treats every terminal state as NOT active', () => {
    for (const s of ['CONFIRMED', 'CANCELLED', 'EXPIRED', 'CANCELLED_SHELTER_DEACTIVATED']) {
      expect(isActive(s)).toBe(false);
    }
  });

  it('treats unknown strings as NOT active (safe default)', () => {
    expect(isActive('')).toBe(false);
    expect(isActive('PENDING')).toBe(false);
    expect(isActive('held')).toBe(false); // case-sensitive by design
  });
});

describe('statusLabelId', () => {
  it('maps every known status to its i18n key', () => {
    expect(statusLabelId('HELD')).toBe('myHolds.status.held');
    expect(statusLabelId('CONFIRMED')).toBe('myHolds.status.confirmed');
    expect(statusLabelId('CANCELLED')).toBe('myHolds.status.cancelled');
    expect(statusLabelId('EXPIRED')).toBe('myHolds.status.expired');
    expect(statusLabelId('CANCELLED_SHELTER_DEACTIVATED'))
      .toBe('myHolds.status.cancelledShelterDeactivated');
  });

  it('falls back to a safe unknown key rather than leaking the raw enum', () => {
    // K-1 discipline from Phase 1: never render the raw enum value to users.
    expect(statusLabelId('')).toBe('myHolds.status.unknown');
    expect(statusLabelId('SOMETHING_NEW')).toBe('myHolds.status.unknown');
    expect(statusLabelId('held')).toBe('myHolds.status.unknown');
  });
});

describe('statusBadgeColors', () => {
  // Round-2 war-room M-3 fix: assert EXACT design tokens per branch,
  // not just "returns truthy strings." A typo swapping palettes
  // (HELD → error instead of warning) would pass the old assertions;
  // this stronger form catches it.

  it('assigns HELD the warning palette (in-flight work)', () => {
    expect(statusBadgeColors('HELD')).toEqual({
      bg: color.warningBg,
      fg: color.warning,
    });
  });

  it('assigns CONFIRMED the success palette', () => {
    expect(statusBadgeColors('CONFIRMED')).toEqual({
      bg: color.successBg,
      fg: color.success,
    });
  });

  it('groups CANCELLED, EXPIRED, and CANCELLED_SHELTER_DEACTIVATED under the error palette', () => {
    const expected = { bg: color.errorBg, fg: color.error };
    // All three share the same palette — treating them distinctly would
    // invite inconsistent UI for semantically-equivalent rows.
    expect(statusBadgeColors('CANCELLED')).toEqual(expected);
    expect(statusBadgeColors('EXPIRED')).toEqual(expected);
    expect(statusBadgeColors('CANCELLED_SHELTER_DEACTIVATED')).toEqual(expected);
  });

  it('returns the neutral fallback for unknown statuses (forward-compat)', () => {
    expect(statusBadgeColors('SOMETHING_NEW')).toEqual({
      bg: color.bgSecondary,
      fg: color.textTertiary,
    });
    // And must differ from HELD so a new backend status doesn't silently
    // render identically to an active hold.
    expect(statusBadgeColors('SOMETHING_NEW')).not.toEqual(statusBadgeColors('HELD'));
  });
});
