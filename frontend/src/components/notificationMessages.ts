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
  // FIXME(session7+): if BOTH data.status and payload.status are missing,
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
    default:
      return 'notifications.unknown';
  }
}

/**
 * Extract the interpolation values for the notification's i18n message.
 * Reads both the direct `data.*` fields (live SSE) and the parsed
 * `payload.*` fields (persistent) so callers always get a populated
 * string even when the notification was loaded from the DB.
 */
export function getNotificationMessageValues(
  notification: Notification,
): Record<string, string> {
  const { data } = notification;
  const payload = parseNotificationPayload(data);
  return {
    shelterName: String(data.shelterName || payload.shelterName || ''),
    status: String(data.status || payload.status || ''),
    count: String(data.count || ''),
  };
}

/**
 * Map a notification event type to the destination path the bell
 * should navigate to when the user clicks the notification row.
 * Independent of the payload shape bug — included here only to keep
 * all notification-message helpers in one module.
 */
export function getNavigationPath(eventType: string): string {
  switch (eventType) {
    case 'dv-referral.responded':
    case 'availability.updated':
    case 'referral.responded':
    case 'reservation.expired':
      return '/outreach';
    case 'dv-referral.requested':
    case 'referral.requested':
    case 'escalation.1h':
    case 'escalation.2h':
    case 'escalation.3_5h':
    case 'escalation.4h':
    case 'surge.activated':
    case 'surge.deactivated':
      return '/coordinator';
    default:
      return '/';
  }
}
