import { describe, it, expect } from 'vitest';
import { matchByPayload, type RawNotification } from './notificationMarkActed';

/**
 * Unit tests for the pure match helper that powers
 * {@link markNotificationsActedByPayload}. The network-layer fetch + PATCH
 * loop is intentionally NOT tested here — it's a thin call-and-Promise.all
 * shape that is covered end-to-end by Phase 4 Playwright test 11.7
 * (markActed on accept). These tests pin the match determinism: raw-row
 * payload shape, exact-equality match, and the malformed-payload guards.
 */

function row(id: string, type: string, payload: Record<string, unknown>): RawNotification {
  return {
    id,
    type,
    severity: 'ACTION_REQUIRED',
    payload: JSON.stringify(payload),
    readAt: null,
    actedAt: null,
    createdAt: new Date().toISOString(),
  };
}

describe('matchByPayload', () => {
  it('returns an empty array when payloadValue is empty', () => {
    expect(matchByPayload([row('n1', 'referral.requested', { referralId: 'ref-1' })], 'referralId', ''))
      .toEqual([]);
  });

  it('returns an empty array when no rows match', () => {
    const rows = [row('n1', 'referral.requested', { referralId: 'ref-other' })];
    expect(matchByPayload(rows, 'referralId', 'ref-wanted')).toEqual([]);
  });

  it('matches a single row on exact value equality', () => {
    const matching = row('n1', 'referral.requested', { referralId: 'ref-X' });
    const other = row('n2', 'referral.requested', { referralId: 'ref-Y' });
    expect(matchByPayload([matching, other], 'referralId', 'ref-X')).toEqual([matching]);
  });

  it('matches multiple rows carrying the same referralId (request + escalations)', () => {
    // The canonical scenario from the measurement gate: 1 referral.requested
    // + 4 escalations = 5 matches per referralId. markActed fans out to all.
    const request = row('n1', 'referral.requested', { referralId: 'ref-X' });
    const esc1 = row('n2', 'escalation.1h', { referralId: 'ref-X' });
    const esc2 = row('n3', 'escalation.2h', { referralId: 'ref-X' });
    const other = row('n4', 'referral.requested', { referralId: 'ref-other' });
    const result = matchByPayload([request, esc1, esc2, other], 'referralId', 'ref-X');
    expect(result).toHaveLength(3);
    expect(result.map((r) => r.id).sort()).toEqual(['n1', 'n2', 'n3']);
  });

  it('does NOT match partial-string values (exact equality only)', () => {
    // Defense against substring-match bugs: if the field is 'ref-X-prefix',
    // asking for 'ref-X' must return zero matches.
    const r = row('n1', 'referral.requested', { referralId: 'ref-X-prefix' });
    expect(matchByPayload([r], 'referralId', 'ref-X')).toEqual([]);
  });

  it('skips rows whose payload lacks the field entirely', () => {
    const r = row('n1', 'referral.requested', { otherField: 'whatever' });
    expect(matchByPayload([r], 'referralId', 'ref-X')).toEqual([]);
  });

  it('handles reservationId field for hold-cancellation deep-links', () => {
    const r = row('n1', 'HOLD_CANCELLED_SHELTER_DEACTIVATED', {
      reservationId: 'res-42',
      shelterId: 'sh-7',
    });
    expect(matchByPayload([r], 'reservationId', 'res-42')).toEqual([r]);
    expect(matchByPayload([r], 'reservationId', 'res-wrong')).toEqual([]);
  });

  it('handles shelterId field for shelter-deactivation deep-links', () => {
    const r = row('n1', 'SHELTER_DEACTIVATED', { shelterId: 'sh-7' });
    expect(matchByPayload([r], 'shelterId', 'sh-7')).toEqual([r]);
  });

  it('ignores non-string payload field values (defensive against schema drift)', () => {
    const r = row('n1', 'referral.requested', { referralId: 12345 });
    expect(matchByPayload([r], 'referralId', '12345')).toEqual([]);
  });

  it('survives malformed payload JSON without throwing', () => {
    const broken: RawNotification = {
      id: 'n1',
      type: 'referral.requested',
      severity: 'ACTION_REQUIRED',
      payload: '{not valid json',
      readAt: null,
      actedAt: null,
      createdAt: new Date().toISOString(),
    };
    expect(matchByPayload([broken], 'referralId', 'ref-X')).toEqual([]);
  });

  it('rejects payloads that parse to null, arrays, or scalars', () => {
    const nullPayload: RawNotification = { ...row('n1', 'x', {}), payload: 'null' };
    const arrayPayload: RawNotification = { ...row('n2', 'x', {}), payload: '[1,2,3]' };
    const stringPayload: RawNotification = { ...row('n3', 'x', {}), payload: '"hello"' };
    expect(matchByPayload([nullPayload, arrayPayload, stringPayload], 'referralId', 'ref-X'))
      .toEqual([]);
  });
});
