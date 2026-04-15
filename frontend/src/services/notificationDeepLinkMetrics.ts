import { api } from './api';

/**
 * Phase 4 task 9a.1 of notification-deep-linking — one-call metrics
 * reporting from useDeepLink host effects. POSTs to
 * /api/v1/metrics/notification-deeplink-click with the notification type
 * and the terminal outcome of the deep-link state machine (D-12):
 * success when it reached {@code done}, stale for any terminal stale
 * reason, or offline when the browser reported offline at failure time.
 *
 * <p>Fire-and-forget — callers should not block on this. Silently
 * swallows errors (metric reporting must never block the main UX flow).
 * The role tag is derived server-side from the authenticated user so the
 * caller cannot forge it; see NotificationMetricsController.reportDeepLinkClick.</p>
 */

export type DeepLinkClickOutcome = 'success' | 'stale' | 'offline';

/**
 * Pure helper — decide the outcome tag from a useDeepLink terminal state.
 * Extracted so host callers don't each reinvent the mapping and so the
 * 404/403/timeout → stale AND navigator.onLine === false → offline
 * decisions are unit-testable.
 */
export function classifyDeepLinkOutcome(
  kind: 'done' | 'stale',
  staleReason: 'not-found' | 'race' | 'error' | 'timeout' | undefined,
  isOffline: boolean,
): DeepLinkClickOutcome {
  if (kind === 'done') return 'success';
  // stale kind. If the browser was offline when the resolve fetch failed,
  // report 'offline' — the backend can't observe offline state directly,
  // this is the only place it gets tagged. error-reason paired with
  // !navigator.onLine is the specific shape notificationMarkActed uses.
  if (staleReason === 'error' && isOffline) return 'offline';
  return 'stale';
}

/**
 * Report a deep-link click outcome to the backend metrics counter.
 * Silent on failure — metrics must not impact the user flow.
 */
export async function reportDeepLinkClick(
  type: string,
  outcome: DeepLinkClickOutcome,
): Promise<void> {
  try {
    await api.post<void>('/api/v1/metrics/notification-deeplink-click', { type, outcome });
  } catch {
    /* best-effort — silent failure by design, task 7.1 L-8 tracks
       diagnostic logging app-wide */
  }
}
