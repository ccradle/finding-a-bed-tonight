import { api } from './api';

/**
 * Outcome of a user's interaction with a deep-linked notification.
 *
 * - {@code 'acted'} — the user successfully completed the operational
 *   response (accepted a referral, confirmed/cancelled a hold, etc.).
 *   PATCH /api/v1/notifications/{id}/acted records the completion; the
 *   notification bell then shows it as acted (three-state visuals — task 7.4).
 *
 * - {@code 'stale'} — the user saw the notification but did not complete
 *   the workflow (the deep-link target was gone, the user dismissed without
 *   acting, etc.). PATCH /api/v1/notifications/{id}/read records that it
 *   was seen without marking it acted. Preserves the distinction between
 *   "I acted" and "I saw too late" in lifecycle state (Design D3).
 */
export type MarkActedOutcome = 'acted' | 'stale';

/**
 * Raw notification row as returned by {@code GET /api/v1/notifications}.
 * The {@code payload} field is a JSON-stringified object whose shape
 * depends on the notification {@code type}; callers extract deep-link
 * identifiers (referralId, reservationId, shelterId) from it.
 */
export interface RawNotification {
  id: string;
  type: string;
  severity: string;
  payload: string;
  readAt: string | null;
  actedAt: string | null;
  createdAt: string;
}

interface NotificationListResponse {
  items: RawNotification[];
  page: number;
  size: number;
  hasMore: boolean;
}

/**
 * Default window size for the lookup fetch. Matches the bell's
 * {@code MAX_NOTIFICATIONS} ceiling so "the user saw this notification
 * in their bell and is now acting on it" produces a match. Older
 * notifications outside the window are statistically rare and the miss
 * is acceptable — the lifecycle state doesn't change, but the backend
 * audit record of the user's action is still written by the domain
 * endpoint (accept, confirm, cancel) that runs alongside this helper.
 */
const LOOKUP_PAGE_SIZE = 50;

/**
 * Mark every notification whose payload field matches the given value
 * as acted (successful action) or read (stale fallback) by the calling user.
 *
 * <p>Phase 3 task 7.1 of notification-deep-linking. Operationalizes D3:
 * {@code markActed} only fires on SUCCESSFUL terminal actions; the
 * {@code 'stale'} outcome marks the notification read (via {@code /read})
 * without marking it acted, preserving the "I saw it but did not complete"
 * distinction.</p>
 *
 * <p>The helper is self-contained — it fetches the caller's current
 * notification window, filters by payload match, and PATCHes each row
 * in parallel. Callers (CoordinatorDashboard, DvEscalationsTab modal,
 * MyPastHoldsPage) fire this after a successful domain action:</p>
 *
 * <pre>
 *   await api.patch(`/api/v1/dv-referrals/${id}/accept`, {});
 *   await markNotificationsActedByPayload('referralId', id, 'acted');
 * </pre>
 *
 * <p>Returns the count of notifications affected so callers can log or
 * trace the effect. Failing PATCHes reject the batch (via Promise.all);
 * the caller should treat partial failures as "try again" signals rather
 * than silently proceeding — the measurement gate (task 7.1a) caps the
 * call path at ≤ 5 round trips and ≤ 500ms wall time for the expected
 * case of 1 request + 4 escalations.</p>
 */
export async function markNotificationsActedByPayload(
  payloadField: 'referralId' | 'reservationId' | 'shelterId',
  payloadValue: string,
  outcome: MarkActedOutcome = 'acted',
): Promise<number> {
  if (!payloadValue) return 0;
  // Fetch the user's recent notifications (includes both unread AND
  // read-but-not-acted). The default endpoint call without ?unread=true
  // returns all lifecycle states.
  const response = await api.get<NotificationListResponse>(
    `/api/v1/notifications?page=0&size=${LOOKUP_PAGE_SIZE}`,
  );
  const matches = matchByPayload(response?.items ?? [], payloadField, payloadValue);
  if (matches.length === 0) return 0;
  const endpoint = outcome === 'acted' ? 'acted' : 'read';
  await Promise.all(
    matches.map((n) => api.patch<void>(`/api/v1/notifications/${n.id}/${endpoint}`, {})),
  );
  return matches.length;
}

/**
 * Pure helper — filters raw notification rows to those whose payload
 * field matches the value. Exported for unit-testing the match
 * determinism independently of the network layer.
 */
export function matchByPayload(
  items: readonly RawNotification[],
  payloadField: string,
  payloadValue: string,
): RawNotification[] {
  if (!payloadValue) return [];
  return items.filter((n) => {
    if (typeof n.payload !== 'string') return false;
    try {
      const parsed: unknown = JSON.parse(n.payload);
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return false;
      const val = (parsed as Record<string, unknown>)[payloadField];
      return typeof val === 'string' && val === payloadValue;
    } catch {
      return false;
    }
  });
}
