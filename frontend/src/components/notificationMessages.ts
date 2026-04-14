/**
 * Notification message helpers — extracted from NotificationBell.tsx so
 * they can be unit-tested in isolation without mounting React components.
 *
 * These functions translate a {@link Notification} into the i18n message
 * id and the values object that the bell UI renders. They must handle
 * two data shapes because the notification layer has two origins:
 *
 * 1. **Persistent notifications** loaded from the DB. The backend stores
 *    the domain-event payload as JSONB and serializes it as a JSON
 *    **string** under `data.payload` when returning the row via
 *    `GET /api/v1/notifications`. Fields like `status` and `shelterName`
 *    live INSIDE that string and must be `JSON.parse`d before reading.
 *
 * 2. **Live SSE domain events** pushed during an active session. These
 *    carry their fields directly on `data` — `data.status`,
 *    `data.shelterName`, etc. — with no string wrapper.
 *
 * Forgetting the first shape is the class of bug that caused
 * dv-outreach to see "rejected" text for accepted referrals on live
 * findabed.org from v0.31.0 through v0.32.2 (reported 2026-04-11).
 * Every read path through this module MUST check both shapes. The
 * {@link parseNotificationPayload} helper + the `data.x ?? payload.x`
 * pattern below are the canonical form. Do not reintroduce the
 * single-shape read.
 */

import type { IntlShape } from 'react-intl';
import type { Notification } from '../hooks/useNotifications';

/**
 * Parse the JSON-stringified payload of a persistent notification.
 * Returns an empty object if the payload is absent, not a string, or
 * malformed JSON — **never throws and never returns a non-object**.
 *
 * <p>The non-object guard is load-bearing: {@code JSON.parse} can return
 * {@code null}, arrays, strings, numbers, and booleans for inputs like
 * {@code 'null'}, {@code '[1,2,3]'}, {@code '"hello"'}, {@code '42'},
 * and {@code 'true'}. Returning any of those from this helper would
 * either crash the caller (reading a property off {@code null} is a
 * {@code TypeError}) or silently mislead it (reading properties off a
 * string or number returns {@code undefined}). Only plain-object
 * parse results are accepted; everything else falls through to the
 * safe empty-object return.</p>
 *
 * <p>Live SSE domain events have their fields directly on {@code data}
 * and pass through without the payload layer; the
 * {@code data.x || payload.x} fallback in the callers handles both
 * shapes uniformly.</p>
 */
export function parseNotificationPayload(
  data: Record<string, unknown>,
): Record<string, unknown> {
  if (typeof data.payload === 'string') {
    try {
      const parsed: unknown = JSON.parse(data.payload);
      // Only accept plain objects. null, arrays, strings, numbers,
      // and booleans all fall through to the safe empty-object return
      // so the caller can read `payload.status` without crashing.
      if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return parsed as Record<string, unknown>;
      }
    } catch {
      /* malformed payload — fall through to empty object */
    }
  }
  return {};
}

/**
 * Map a notification to the i18n message id that describes it. The
 * `referral.responded` branches read `status` from both the direct
 * `data.status` (live SSE) and the parsed `payload.status` (persistent)
 * so BOTH shapes render the correct accepted-vs-rejected copy.
 */
export function getNotificationMessageId(notification: Notification): string {
  const { eventType, data } = notification;
  const payload = parseNotificationPayload(data);
  // status may live on data directly (live SSE push) or inside the
  // JSON-stringified payload (persistent notification loaded from DB).
  // Missing the second path is the bug this module was extracted to fix.
  //
  // `||` (not `??`) matches the sibling getNotificationMessageValues
  // pattern and also treats empty string as "no value" — backend only
  // publishes non-empty "ACCEPTED"/"REJECTED" so both operators work,
  // but consistency across the two helpers prevents a latent footgun
  // if a future refactor introduces legitimate falsy values.
  //
  // TODO(session7+, task #126): if BOTH data.status and payload.status are missing,
  // the `===  'ACCEPTED'` check below falls through to
  // 'notifications.referralRejected', which is misleading for a truly
  // malformed notification (neither accepted nor rejected). Consider
  // routing missing-status to 'notifications.unknown' in a future
  // iteration. Pinned by the "missing status field on both shapes"
  // test in notificationMessages.test.ts.
  const status = data.status || payload.status;
  switch (eventType) {
    case 'dv-referral.responded':
    case 'referral.responded':
      return status === 'ACCEPTED'
        ? 'notifications.referralAccepted'
        : 'notifications.referralRejected';
    case 'dv-referral.requested':
    case 'referral.requested':
      return 'notifications.referralRequested';
    case 'availability.updated':
      return 'notifications.availabilityUpdated';
    case 'surge.activated':
      return 'notifications.surgeActivated';
    case 'surge.deactivated':
      return 'notifications.surgeDeactivated';
    case 'reservation.expired':
      return 'notifications.reservationExpired';
    case 'escalation.1h':
      return 'notifications.escalation1h';
    case 'escalation.2h':
      return 'notifications.escalation2h';
    case 'escalation.3_5h':
      return 'notifications.escalation3_5h';
    case 'escalation.4h':
      return 'notifications.escalation4h';
    case 'SHELTER_DEACTIVATED':
      return 'notifications.shelterDeactivated';
    case 'HOLD_CANCELLED_SHELTER_DEACTIVATED':
      return 'notifications.holdCancelledShelterDeactivated';
    case 'referral.reassigned':
      return 'notifications.referralReassigned';
    default:
      return 'notifications.unknown';
  }
}

/**
 * Extract the interpolation values for the notification's i18n message.
 * Reads both the direct `data.*` fields (live SSE) and the parsed
 * `payload.*` fields (persistent) so callers always get a populated
 * string even when the notification was loaded from the DB.
 *
 * <p>The optional {@code intl} parameter enables localization of enum
 * values before they reach the rendered string — currently the shelter
 * deactivation reason. Without {@code intl}, the raw enum value
 * (e.g., "TEMPORARY_CLOSURE") would surface to end users, which Keisha
 * flagged as the K-1 regression in the notification-deep-linking war
 * room. Production callers (NotificationBell.tsx) MUST pass {@code intl};
 * tests and other non-i18n callers may omit it and accept the raw
 * enum.</p>
 */
export function getNotificationMessageValues(
  notification: Notification,
  intl?: IntlShape,
): Record<string, string> {
  const { data } = notification;
  const payload = parseNotificationPayload(data);
  const rawReason = String(data.reason || payload.reason || '');
  // K-1 fix: resolve shelter.reason.TEMPORARY_CLOSURE → "Temporary closure"
  // using the i18n keys shipped in v0.38.0. Fall back to the raw enum
  // value only when intl isn't available (unit tests) or the key is
  // missing — intl.formatMessage returns the id verbatim when unmatched,
  // so an unknown reason surfaces as "shelter.reason.XXX" which is
  // easier to debug than a silent empty string.
  const localizedReason = rawReason && intl
    ? intl.formatMessage({ id: `shelter.reason.${rawReason}` })
    : rawReason;
  return {
    shelterName: String(data.shelterName || payload.shelterName || ''),
    status: String(data.status || payload.status || ''),
    count: String(data.count || ''),
    reason: rawReason,
    localizedReason,
  };
}

/**
 * From a list of notifications, pick the referralId of the oldest unread
 * CRITICAL escalation — the one with the least remaining time, therefore
 * the one a coordinator should respond to first. Returns {@code null} when
 * no escalation notification with a referralId is present.
 *
 * <p>Used by {@code CriticalNotificationBanner}'s coordinator CTA
 * (notification-deep-linking Phase 2 task 5.1 / X-4). The X-4 requirement
 * is that "first" be deterministic — reading the oldest-timestamp
 * escalation keeps the answer stable across renders and network
 * deliveries, and matches the "most urgent first" mental model coordinators
 * expect.</p>
 *
 * <p>Reads {@code referralId} from both notification shapes via
 * {@link parseNotificationPayload}: live SSE (field on {@code data}
 * directly) and persistent (inside the JSON-stringified {@code payload}).
 * Skips escalation notifications whose payload is missing a referralId —
 * those could not be routed anyway.</p>
 *
 * <p>Extracted as a pure helper so the determinism contract can be unit-
 * tested independently of React rendering (war-room M-1 fix).</p>
 */
export function pickOldestEscalationReferralId(
  notifications: Notification[],
): string | null {
  const candidates: Array<{ n: Notification; referralId: string }> = [];
  for (const n of notifications) {
    const type = n.eventType || n.data.type;
    if (typeof type !== 'string' || !type.startsWith('escalation.')) continue;
    const payload = parseNotificationPayload(n.data);
    const rawReferralId = n.data.referralId ?? payload.referralId;
    if (typeof rawReferralId !== 'string' || rawReferralId === '') continue;
    candidates.push({ n, referralId: rawReferralId });
  }
  if (candidates.length === 0) return null;
  // Stable ascending sort on timestamp (oldest first). When timestamps tie,
  // keep the original order so the result is still deterministic.
  candidates.sort((a, b) => a.n.timestamp - b.n.timestamp);
  return candidates[0].referralId;
}

/**
 * Pick the default role-based landing page when a notification carries
 * no deep-link identifier (pre-change notifications, malformed payload,
 * or types that never deep-link). Mirrors the priority order used by
 * {@code AuthGuard.getDefaultRouteForRoles} but owns its own copy so
 * this module stays decoupled from the auth guard; the two only need
 * to agree on the role-to-default mapping, not share code.
 */
function getRoleBasedDefaultPath(userRoles: string[]): string {
  if (userRoles.includes('PLATFORM_ADMIN') || userRoles.includes('COC_ADMIN')) {
    return '/admin';
  }
  if (userRoles.includes('COORDINATOR')) {
    return '/coordinator';
  }
  if (userRoles.includes('OUTREACH_WORKER')) {
    return '/outreach';
  }
  return '/';
}

/**
 * Map a notification to the destination path the bell should navigate
 * to when the user clicks the notification row.
 *
 * <p>Role-aware (notification-deep-linking OpenSpec / Issue #106):
 * the same notification type may land admin on the escalation queue
 * but a coordinator on the specific referral in their dashboard.</p>
 *
 * <p>Identifiers (referralId, shelterId, reservationId) are read
 * through {@link parseNotificationPayload} to handle both the live-SSE
 * shape (fields on {@code data} directly) and the persistent shape
 * (fields inside the JSON-stringified {@code data.payload}).</p>
 *
 * <p>Graceful fallback: if the expected identifier is missing
 * (pre-change notifications), the function falls back to the role-based
 * default path rather than constructing a broken URL.</p>
 */
export function getNavigationPath(
  notification: Notification,
  userRoles: string[],
): string {
  const { eventType, data } = notification;
  const payload = parseNotificationPayload(data);

  // Identifiers may live on data directly (live SSE) or inside the
  // JSON-stringified payload (persistent notification) — read both.
  const referralId = String(data.referralId || payload.referralId || '');
  const shelterId = String(data.shelterId || payload.shelterId || '');
  const reservationId = String(data.reservationId || payload.reservationId || '');

  const isAdmin = userRoles.includes('COC_ADMIN') || userRoles.includes('PLATFORM_ADMIN');
  const isCoordinator = userRoles.includes('COORDINATOR');

  switch (eventType) {
    // Escalations + new-referral: admin → queue, coordinator → specific referral.
    case 'escalation.1h':
    case 'escalation.2h':
    case 'escalation.3_5h':
    case 'escalation.4h':
    case 'dv-referral.requested':
    case 'referral.requested':
    case 'referral.reassigned':
      if (isAdmin) {
        return referralId
          ? `/admin#dvEscalations?referralId=${referralId}`
          : '/admin#dvEscalations';
      }
      if (isCoordinator) {
        return referralId
          ? `/coordinator?referralId=${referralId}`
          : '/coordinator';
      }
      return getRoleBasedDefaultPath(userRoles);

    // Shelter deactivation broadcast — coordinators see their dashboard
    // scoped to that shelter, admins see the admin panel scoped to it.
    case 'SHELTER_DEACTIVATED':
      if (isAdmin) {
        return shelterId ? `/admin?shelterId=${shelterId}` : '/admin';
      }
      if (isCoordinator) {
        return shelterId ? `/coordinator?shelterId=${shelterId}` : '/coordinator';
      }
      return getRoleBasedDefaultPath(userRoles);

    // Outreach worker — their bed hold was cancelled because the
    // shelter was deactivated. Land on My Past Holds with row highlighted.
    case 'HOLD_CANCELLED_SHELTER_DEACTIVATED':
      return reservationId
        ? `/outreach/my-holds?reservationId=${reservationId}`
        : '/outreach';

    // Outreach worker — their held reservation expired on its own.
    case 'reservation.expired':
      return reservationId
        ? `/outreach/my-holds?reservationId=${reservationId}`
        : '/outreach';

    case 'dv-referral.responded':
    case 'referral.responded':
    case 'availability.updated':
      return '/outreach';

    case 'surge.activated':
    case 'surge.deactivated':
      return isCoordinator ? '/coordinator' : getRoleBasedDefaultPath(userRoles);

    default:
      return getRoleBasedDefaultPath(userRoles);
  }
}
