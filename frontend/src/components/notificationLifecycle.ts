import type { Notification } from '../hooks/useNotifications';

/**
 * Three-state lifecycle of a notification per Design D7 (Phase 3 task 7.4
 * of notification-deep-linking):
 *
 * - {@code 'unread'} — user has not seen it. Bell shows background highlight,
 *   semibold text; badge counter includes it.
 * - {@code 'pending'} — user has seen it (read) but has not completed the
 *   terminal action. Bell shows normal background, normal weight, small
 *   "• Pending" indicator. Badge does NOT count it.
 * - {@code 'acted'} — user has completed the terminal action (accepted a
 *   referral, confirmed/cancelled a hold, etc.). Bell shows muted text, small
 *   "✓ Completed" indicator. Hidden when the "Hide acted" filter is on.
 */
export type NotificationLifecycleState = 'unread' | 'pending' | 'acted';

/**
 * Derive the lifecycle state from a notification's read + acted flags.
 *
 * <p>Extracted as a pure helper so the 3-branch decision can be unit-tested
 * in isolation — war-room M-1 from Phase 3 session 4. The 3 render
 * branches (body style / label i18n key / tooltip i18n key) all route
 * through this enum so a typo in one place can't cause the state to
 * diverge silently between visuals.</p>
 *
 * <p>{@code acted} beats {@code read} — once the user has acted, the acted
 * state wins even though the user has also (by definition) read it.</p>
 */
export function deriveLifecycleState(notification: Notification): NotificationLifecycleState {
  if (notification.acted) return 'acted';
  if (notification.read) return 'pending';
  return 'unread';
}

/**
 * Map a lifecycle state to the i18n key used for the bell row's
 * {@code aria-label} state word ("Unread" / "Pending action" / "Completed").
 */
export function stateLabelIdFor(state: NotificationLifecycleState): string {
  switch (state) {
    case 'unread':
      return 'notifications.state.unread';
    case 'pending':
      return 'notifications.state.pending';
    case 'acted':
      return 'notifications.state.acted';
  }
}

/**
 * Map a lifecycle state to the i18n key used for the native-tooltip
 * {@code title=} text (Rev. Monroe M-1 from task 7.4a). Tooltip text
 * explains what the state means when the user hovers or focuses the
 * state indicator.
 */
export function stateTooltipIdFor(state: NotificationLifecycleState): string {
  switch (state) {
    case 'unread':
      return 'notifications.state.unreadTooltip';
    case 'pending':
      return 'notifications.state.pendingTooltip';
    case 'acted':
      return 'notifications.state.actedTooltip';
  }
}

/**
 * Map a non-unread lifecycle state to the short label rendered INSIDE the
 * row (next to the ✓ or • indicator). Returns null for the unread state
 * because the bell doesn't render an inline indicator for unread — the
 * background highlight + badge already convey that signal.
 */
export function stateInlineLabelIdFor(state: NotificationLifecycleState): string | null {
  switch (state) {
    case 'unread':
      return null;
    case 'pending':
      return 'notifications.state.pendingLabel';
    case 'acted':
      return 'notifications.state.actedLabel';
  }
}
