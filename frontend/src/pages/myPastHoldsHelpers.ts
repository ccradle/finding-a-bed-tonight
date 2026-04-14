import { color } from '../theme/colors';

/**
 * Pure helpers extracted from MyPastHoldsPage so their branches can be
 * unit-tested without rendering React (war-room Phase 3 session 1 M-1).
 *
 * <p>Kept in a sibling module rather than inlined so a status typo in one
 * branch of {@link statusLabelId} surfaces as a failing Vitest assertion
 * rather than a visual bug the Playwright test might not distinguish
 * from other rendering glitches.</p>
 */

/**
 * Treat HELD as "Active" — the row the outreach worker may still act on.
 * Every other status is a terminal state shown in the Recent group.
 */
export function isActive(status: string): boolean {
  return status === 'HELD';
}

/**
 * Map a reservation status to its i18n key. Keeps status labels
 * centralized so locale changes don't fan out to every render site.
 * The default branch yields a safe fallback rather than silently
 * showing the raw enum to users (matches the shelter.reason.* pattern
 * from Phase 1 K-1).
 */
export function statusLabelId(status: string): string {
  switch (status) {
    case 'HELD':
      return 'myHolds.status.held';
    case 'CONFIRMED':
      return 'myHolds.status.confirmed';
    case 'CANCELLED':
      return 'myHolds.status.cancelled';
    case 'EXPIRED':
      return 'myHolds.status.expired';
    case 'CANCELLED_SHELTER_DEACTIVATED':
      return 'myHolds.status.cancelledShelterDeactivated';
    default:
      return 'myHolds.status.unknown';
  }
}

/**
 * Background + foreground pair for the status badge — resolved against
 * the design-system tokens so dark-mode + contrast carry over for free.
 * Returns the neutral fallback for unknown statuses so a new backend
 * status never breaks the render.
 */
export function statusBadgeColors(status: string): { bg: string; fg: string } {
  switch (status) {
    case 'HELD':
      return { bg: color.warningBg, fg: color.warning };
    case 'CONFIRMED':
      return { bg: color.successBg, fg: color.success };
    case 'CANCELLED':
    case 'EXPIRED':
    case 'CANCELLED_SHELTER_DEACTIVATED':
      return { bg: color.errorBg, fg: color.error };
    default:
      return { bg: color.bgSecondary, fg: color.textTertiary };
  }
}
