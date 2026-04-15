import { describe, it, expect } from 'vitest';
import type { IntlShape } from 'react-intl';
import {
  parseNotificationPayload,
  getNotificationMessageId,
  getNotificationMessageValues,
  getNavigationPath,
  pickOldestEscalationReferralId,
} from './notificationMessages';
import type { Notification } from '../hooks/useNotifications';

/**
 * Regression guards for the v0.32.3 notification rendering hotfix.
 *
 * The bug (reported 2026-04-11, introduced in commit 8ebb666, shipped in
 * v0.31.0 through v0.32.2): every persistent `referral.responded`
 * notification rendered as "rejected" regardless of actual status,
 * because `getNotificationMessageId` read `data.status` but the status
 * for persistent notifications lives inside `data.payload` as a
 * JSON-stringified field.
 *
 * These tests exercise BOTH notification data shapes in BOTH directions
 * (accepted + rejected) across BOTH event type spellings
 * (`dv-referral.responded` live push + `referral.responded` persistent).
 * If someone reintroduces the single-shape read, all four `.referral*`
 * tests fail loudly.
 */

/** Build a live-SSE-shaped notification (fields directly on data). */
function liveNotification(eventType: string, data: Record<string, unknown>): Notification {
  return {
    id: 'test-id',
    eventType,
    data,
    timestamp: Date.now(),
    read: false,
    acted: false,
  };
}

/** Build a persistent-shape notification (fields inside a JSON-string payload). */
function persistentNotification(
  eventType: string,
  payload: Record<string, unknown>,
): Notification {
  return {
    id: 'test-id',
    eventType,
    data: { payload: JSON.stringify(payload) },
    timestamp: Date.now(),
    read: false,
    acted: false,
  };
}

describe('parseNotificationPayload', () => {
  it('returns the parsed JSON object when data.payload is a valid JSON string', () => {
    const parsed = parseNotificationPayload({
      payload: '{"status":"ACCEPTED","shelterName":"Harbor House"}',
    });
    expect(parsed).toEqual({ status: 'ACCEPTED', shelterName: 'Harbor House' });
  });

  it('returns an empty object when data.payload is missing', () => {
    expect(parseNotificationPayload({})).toEqual({});
  });

  it('returns an empty object when data.payload is not a string (already an object)', () => {
    // Some call paths may unbundle the payload before it reaches the helper;
    // treat non-string payloads as "no parseable string here."
    expect(parseNotificationPayload({ payload: { status: 'ACCEPTED' } })).toEqual({});
  });

  it('returns an empty object (not throw) when data.payload is malformed JSON', () => {
    expect(parseNotificationPayload({ payload: '{not valid json' })).toEqual({});
  });

  // The following edge cases were flagged by Marcus Webb in the v0.32.3
  // hotfix war room. JSON.parse can return non-object values for legitimate
  // JSON inputs ("null", "42", "true", '"hello"', "[1,2,3]"). Each must
  // fall through to the safe empty-object return — NOT crash the caller
  // when it tries to read `payload.status` off the result.

  it('returns an empty object (not crash) when JSON.parse returns null', () => {
    // Without the non-object guard, the caller would do `null.status` → TypeError.
    expect(parseNotificationPayload({ payload: 'null' })).toEqual({});
  });

  it('returns an empty object when JSON.parse returns an array', () => {
    expect(parseNotificationPayload({ payload: '[1,2,3]' })).toEqual({});
  });

  it('returns an empty object when JSON.parse returns a string literal', () => {
    expect(parseNotificationPayload({ payload: '"hello"' })).toEqual({});
  });

  it('returns an empty object when JSON.parse returns a number', () => {
    expect(parseNotificationPayload({ payload: '42' })).toEqual({});
  });

  it('returns an empty object when JSON.parse returns a boolean', () => {
    expect(parseNotificationPayload({ payload: 'true' })).toEqual({});
  });
});

describe('getNotificationMessageId — referral.responded (the bug class)', () => {
  // Persistent notifications (DB-backed) — the shape the v0.32.3 bug missed.
  // These four tests are the load-bearing regression guard. They must pass
  // for both event type spellings and in both directions.

  it('persistent ACCEPTED (dv-referral.responded) → referralAccepted', () => {
    const n = persistentNotification('dv-referral.responded', { status: 'ACCEPTED' });
    expect(getNotificationMessageId(n)).toBe('notifications.referralAccepted');
  });

  it('persistent ACCEPTED (referral.responded) → referralAccepted', () => {
    const n = persistentNotification('referral.responded', { status: 'ACCEPTED' });
    expect(getNotificationMessageId(n)).toBe('notifications.referralAccepted');
  });

  it('persistent REJECTED (dv-referral.responded) → referralRejected', () => {
    const n = persistentNotification('dv-referral.responded', { status: 'REJECTED' });
    expect(getNotificationMessageId(n)).toBe('notifications.referralRejected');
  });

  it('persistent REJECTED (referral.responded) → referralRejected', () => {
    const n = persistentNotification('referral.responded', { status: 'REJECTED' });
    expect(getNotificationMessageId(n)).toBe('notifications.referralRejected');
  });

  // Live SSE domain events — the shape that worked before the bug and
  // must continue to work after the fix.

  it('live ACCEPTED (dv-referral.responded) → referralAccepted', () => {
    const n = liveNotification('dv-referral.responded', { status: 'ACCEPTED' });
    expect(getNotificationMessageId(n)).toBe('notifications.referralAccepted');
  });

  it('live REJECTED (dv-referral.responded) → referralRejected', () => {
    const n = liveNotification('dv-referral.responded', { status: 'REJECTED' });
    expect(getNotificationMessageId(n)).toBe('notifications.referralRejected');
  });

  // Edge case flagged by Riley Cho in the v0.32.3 hotfix war room: when
  // status is missing from BOTH shapes (truly malformed notification),
  // the current implementation falls through to 'referralRejected' as
  // the else-branch default. This test pins that behavior so a future
  // refactor knows it's intentional-by-default. The FIXME comment in
  // notificationMessages.ts proposes routing to 'notifications.unknown'
  // in a future iteration; if that change ships, update this test to
  // assert the new behavior.

  it('missing status on both data and payload → falls through to referralRejected (FIXME: should be unknown)', () => {
    const n = liveNotification('referral.responded', {}); // no status anywhere
    expect(getNotificationMessageId(n)).toBe('notifications.referralRejected');
  });

  it('missing status on persistent payload (empty object) → falls through to referralRejected (FIXME)', () => {
    const n = persistentNotification('referral.responded', {}); // payload is "{}"
    expect(getNotificationMessageId(n)).toBe('notifications.referralRejected');
  });
});

describe('getNotificationMessageId — other event types (no regression)', () => {
  it('referral.requested → referralRequested', () => {
    expect(getNotificationMessageId(liveNotification('referral.requested', {}))).toBe(
      'notifications.referralRequested',
    );
  });

  it('availability.updated → availabilityUpdated', () => {
    expect(getNotificationMessageId(liveNotification('availability.updated', {}))).toBe(
      'notifications.availabilityUpdated',
    );
  });

  it('surge.activated → surgeActivated', () => {
    expect(getNotificationMessageId(liveNotification('surge.activated', {}))).toBe(
      'notifications.surgeActivated',
    );
  });

  it('escalation.2h → escalation2h', () => {
    expect(getNotificationMessageId(liveNotification('escalation.2h', {}))).toBe(
      'notifications.escalation2h',
    );
  });

  it('unknown event type → unknown', () => {
    expect(getNotificationMessageId(liveNotification('something.unheard-of', {}))).toBe(
      'notifications.unknown',
    );
  });
});

describe('getNotificationMessageValues', () => {
  it('extracts shelterName from live SSE direct data', () => {
    const n = liveNotification('availability.updated', { shelterName: 'Harbor House' });
    expect(getNotificationMessageValues(n).shelterName).toBe('Harbor House');
  });

  it('extracts shelterName from persistent payload JSON string', () => {
    const n = persistentNotification('availability.updated', { shelterName: 'Harbor House' });
    expect(getNotificationMessageValues(n).shelterName).toBe('Harbor House');
  });

  it('extracts status from live SSE direct data', () => {
    const n = liveNotification('referral.responded', { status: 'ACCEPTED' });
    expect(getNotificationMessageValues(n).status).toBe('ACCEPTED');
  });

  it('extracts status from persistent payload JSON string', () => {
    const n = persistentNotification('referral.responded', { status: 'ACCEPTED' });
    expect(getNotificationMessageValues(n).status).toBe('ACCEPTED');
  });

  it('returns empty strings (not undefined) when fields are missing', () => {
    const n = liveNotification('something', {});
    const values = getNotificationMessageValues(n);
    expect(values.shelterName).toBe('');
    expect(values.status).toBe('');
    expect(values.count).toBe('');
  });
});

/**
 * notification-deep-linking (Issue #106) — getNavigationPath role-aware
 * routing tests. Added per war-room M-1: the role-aware code shipped in
 * commit 65386bb had zero test coverage before these were added.
 *
 * The function takes (notification, userRoles) and returns a deep-link
 * path. Identifiers are read from BOTH live-SSE shape (data.referralId)
 * and persistent shape (payload.referralId via parseNotificationPayload),
 * so we exercise both shapes.
 */
describe('getNavigationPath — role-aware deep-link routing', () => {
  it('escalation.1h + COORDINATOR + referralId → /coordinator?referralId=X', () => {
    const n = liveNotification('escalation.1h', { referralId: 'ref-123' });
    expect(getNavigationPath(n, ['COORDINATOR'])).toBe('/coordinator?referralId=ref-123');
  });

  it('escalation.1h + COC_ADMIN + referralId → /admin#dvEscalations?referralId=X', () => {
    const n = liveNotification('escalation.1h', { referralId: 'ref-123' });
    expect(getNavigationPath(n, ['COC_ADMIN'])).toBe('/admin#dvEscalations?referralId=ref-123');
  });

  it('SHELTER_DEACTIVATED + COORDINATOR + shelterId → /coordinator?shelterId=X', () => {
    // Persistent shape — payload field carries the shelterId.
    const n = persistentNotification('SHELTER_DEACTIVATED', { shelterId: 'sh-456' });
    expect(getNavigationPath(n, ['COORDINATOR'])).toBe('/coordinator?shelterId=sh-456');
  });

  it('HOLD_CANCELLED_SHELTER_DEACTIVATED + OUTREACH_WORKER → /outreach/my-holds?reservationId=X', () => {
    const n = persistentNotification('HOLD_CANCELLED_SHELTER_DEACTIVATED', { reservationId: 'res-789' });
    expect(getNavigationPath(n, ['OUTREACH_WORKER'])).toBe('/outreach/my-holds?reservationId=res-789');
  });

  it('escalation.1h with NO referralId in payload → role-based default fallback', () => {
    // D6: pre-change notifications without identifiers must not break.
    const n = liveNotification('escalation.1h', {});
    expect(getNavigationPath(n, ['COORDINATOR'])).toBe('/coordinator');
    expect(getNavigationPath(n, ['COC_ADMIN'])).toBe('/admin#dvEscalations');
  });

  it('unknown event type → role-based default path', () => {
    const n = liveNotification('something.totally.unknown', {});
    expect(getNavigationPath(n, ['COORDINATOR'])).toBe('/coordinator');
    expect(getNavigationPath(n, ['COC_ADMIN'])).toBe('/admin');
    expect(getNavigationPath(n, ['OUTREACH_WORKER'])).toBe('/outreach');
    expect(getNavigationPath(n, [])).toBe('/');
  });
});

/**
 * Phase 2 task 5.1 / X-4 — determinism contract for the coordinator
 * CriticalNotificationBanner CTA. The "first" escalation must be the
 * oldest one (most urgent, least time before expiry), regardless of
 * input order. Extracted as a pure helper so this contract is testable
 * without rendering the banner component (war-room M-1 fix).
 */
describe('pickOldestEscalationReferralId — banner CTA selection', () => {
  function notif(eventType: string, data: Record<string, unknown>, timestamp: number): Notification {
    return { id: `id-${timestamp}`, eventType, data, timestamp, read: false, acted: false };
  }

  it('returns null when the list is empty', () => {
    expect(pickOldestEscalationReferralId([])).toBeNull();
  });

  it('returns null when no notifications are of type escalation.*', () => {
    const ns = [
      notif('referral.requested', { referralId: 'r1' }, 1),
      notif('availability.updated', { referralId: 'r2' }, 2),
    ];
    expect(pickOldestEscalationReferralId(ns)).toBeNull();
  });

  it('skips escalation notifications that lack a referralId in both data and payload', () => {
    const ns = [
      notif('escalation.1h', { /* no referralId */ }, 1),
    ];
    expect(pickOldestEscalationReferralId(ns)).toBeNull();
  });

  it('picks the oldest escalation by timestamp (ASC, oldest wins)', () => {
    const ns = [
      notif('escalation.2h', { referralId: 'ref-new' }, 200),
      notif('escalation.1h', { referralId: 'ref-old' }, 100),
      notif('escalation.3_5h', { referralId: 'ref-mid' }, 150),
    ];
    expect(pickOldestEscalationReferralId(ns)).toBe('ref-old');
  });

  it('reads referralId from persistent payload shape (JSON-stringified)', () => {
    // Simulates the shape returned from GET /api/v1/notifications: fields
    // live inside data.payload as a JSON string, not on data directly.
    const n: Notification = {
      id: 'persistent-1',
      eventType: 'escalation.1h',
      data: { payload: JSON.stringify({ referralId: 'ref-persistent' }) },
      timestamp: 1,
      read: false,
      acted: false,
    };
    expect(pickOldestEscalationReferralId([n])).toBe('ref-persistent');
  });

  it('prefers data.referralId over payload.referralId when both present (live SSE shape wins)', () => {
    const n: Notification = {
      id: 'mixed-1',
      eventType: 'escalation.1h',
      data: {
        referralId: 'ref-from-data',
        payload: JSON.stringify({ referralId: 'ref-from-payload' }),
      },
      timestamp: 1,
      read: false,
      acted: false,
    };
    expect(pickOldestEscalationReferralId([n])).toBe('ref-from-data');
  });

  it('falls back to data.type when eventType is missing (defensive)', () => {
    // eventType should always be set; this guards the case where a caller
    // passes notifications with only data.type populated (e.g., tests or
    // a future refactor).
    const ns = [
      notif('', { type: 'escalation.1h', referralId: 'ref-from-data-type' }, 1),
    ];
    expect(pickOldestEscalationReferralId(ns)).toBe('ref-from-data-type');
  });
});

/**
 * notification-deep-linking Phase 4 tasks 10.3 + 10.4 — explicit coverage
 * for the three Phase 1 notification types previously rendered as
 * "notifications.unknown": {@code SHELTER_DEACTIVATED},
 * {@code HOLD_CANCELLED_SHELTER_DEACTIVATED}, {@code referral.reassigned}.
 *
 * <p>The {@code getNavigationPath} side is exercised above (task 10.1).
 * These describe blocks pin the i18n-key mapping ({@code getNotificationMessageId})
 * and — for {@code SHELTER_DEACTIVATED} specifically — the K-1 contract
 * from Keisha that {@code getNotificationMessageValues} localizes the
 * reason enum rather than surfacing the raw {@code TEMPORARY_CLOSURE}
 * screen label.</p>
 */

describe('getNotificationMessageId — three new notification types (Phase 1)', () => {
  it('SHELTER_DEACTIVATED maps to notifications.shelterDeactivated', () => {
    const n = persistentNotification('SHELTER_DEACTIVATED', {
      shelterId: 'sh-1',
      shelterName: 'Harbor House',
      reason: 'TEMPORARY_CLOSURE',
    });
    expect(getNotificationMessageId(n)).toBe('notifications.shelterDeactivated');
  });

  it('HOLD_CANCELLED_SHELTER_DEACTIVATED maps to notifications.holdCancelledShelterDeactivated', () => {
    const n = persistentNotification('HOLD_CANCELLED_SHELTER_DEACTIVATED', {
      reservationId: 'res-1',
      shelterName: 'Harbor House',
      reason: 'PERMANENT_CLOSURE',
    });
    expect(getNotificationMessageId(n)).toBe('notifications.holdCancelledShelterDeactivated');
  });

  it('referral.reassigned maps to notifications.referralReassigned', () => {
    const n = persistentNotification('referral.reassigned', {
      referralId: 'ref-1',
    });
    expect(getNotificationMessageId(n)).toBe('notifications.referralReassigned');
  });

  // Regression guard — an unknown type must still fall back to unknown and
  // NOT match one of the three new types by accident (e.g. via substring).
  it('unknown type still falls back to notifications.unknown, not a new-type key', () => {
    const n = persistentNotification('SHELTER_DEACTIVATED_PARTIAL', {});
    expect(getNotificationMessageId(n)).toBe('notifications.unknown');
  });
});

describe('getNotificationMessageValues — K-1: SHELTER_DEACTIVATED reason localization (Keisha)', () => {
  /**
   * Minimal IntlShape stub for the test. Real production calls pass the
   * full IntlShape from {@code useIntl()}; this stub only needs to honor
   * the one method the values function calls, and to simulate a successful
   * lookup by echoing a deterministic "localized" string. If the impl
   * regressed to NOT consult intl at all, the assertion against the stub
   * output would fail.
   */
  function makeIntlStub(lookup: Record<string, string>): IntlShape {
    return {
      formatMessage: ({ id }: { id: string }) =>
        lookup[id] ?? id, // react-intl's fallback-to-id behavior
    } as unknown as IntlShape;
  }

  it('without intl, reason is the raw enum (legacy / non-i18n callers)', () => {
    const n = persistentNotification('SHELTER_DEACTIVATED', {
      shelterName: 'Harbor House',
      reason: 'TEMPORARY_CLOSURE',
    });
    const values = getNotificationMessageValues(n);
    expect(values.reason).toBe('TEMPORARY_CLOSURE');
    // localizedReason has no intl to consult → falls back to raw.
    expect(values.localizedReason).toBe('TEMPORARY_CLOSURE');
  });

  it('with intl, localizedReason resolves via shelter.reason.<enum> key', () => {
    const intl = makeIntlStub({
      'shelter.reason.TEMPORARY_CLOSURE': 'Temporary closure',
    });
    const n = persistentNotification('SHELTER_DEACTIVATED', {
      shelterName: 'Harbor House',
      reason: 'TEMPORARY_CLOSURE',
    });
    const values = getNotificationMessageValues(n, intl);
    // K-1 contract: localizedReason is the friendly string, raw reason is preserved.
    expect(values.localizedReason).toBe('Temporary closure');
    expect(values.reason).toBe('TEMPORARY_CLOSURE');
  });

  it('with intl but missing key, localizedReason falls through to the id (debuggable)', () => {
    // react-intl returns the id when the message is not found in the catalog.
    // The stub mirrors that behavior. The test documents the fallback shape
    // so a future refactor that silently strips unknown reasons fails this test.
    const intl = makeIntlStub({}); // no entries
    const n = persistentNotification('SHELTER_DEACTIVATED', {
      shelterName: 'Harbor House',
      reason: 'BRAND_NEW_REASON_NOT_IN_CATALOG',
    });
    const values = getNotificationMessageValues(n, intl);
    // Surfaces as the i18n id — operators grep logs for 'shelter.reason.*'
    // to find untranslated enum values rather than seeing a silent empty.
    expect(values.localizedReason).toBe('shelter.reason.BRAND_NEW_REASON_NOT_IN_CATALOG');
  });
});
