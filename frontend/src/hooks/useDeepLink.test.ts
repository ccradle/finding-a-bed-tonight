import { describe, it, expect } from 'vitest';
import {
  extractIntent,
  intentsEqual,
  currentIntent,
  deepLinkReducer,
  type DeepLinkState,
  type DeepLinkAction,
  type ResolvedTarget,
} from './useDeepLink';

/**
 * Pure-function tests for the deep-link state machine and helpers.
 *
 * <p>These tests assert the entire D-12 transition table without
 * rendering React. The hook itself is a thin useReducer + useEffect
 * wiring layer; correctness of the machine is covered here, and the
 * wiring's behavior is covered indirectly by the Playwright happy-path
 * test ({@code persistent-notifications.spec.ts}).</p>
 */

interface FakeDetail {
  id: string;
  populationType: string;
}

function resolved(referralId = 'ref-A', shelterId = 'sh-A'): ResolvedTarget<FakeDetail> {
  return {
    intent: { referralId },
    resolvedShelterId: shelterId,
    detail: { id: referralId, populationType: 'DV_SURVIVOR' },
  };
}

describe('extractIntent', () => {
  it('returns null when no deep-link params are present', () => {
    const params = new URLSearchParams('foo=bar&page=2');
    expect(extractIntent(params)).toBeNull();
  });

  it('extracts referralId', () => {
    const params = new URLSearchParams('referralId=abc-123');
    expect(extractIntent(params)).toEqual({
      referralId: 'abc-123',
      shelterId: undefined,
      reservationId: undefined,
    });
  });

  it('extracts shelterId', () => {
    const params = new URLSearchParams('shelterId=sh-456');
    expect(extractIntent(params)).toEqual({
      referralId: undefined,
      shelterId: 'sh-456',
      reservationId: undefined,
    });
  });

  it('extracts reservationId (Phase 3 my-past-holds)', () => {
    const params = new URLSearchParams('reservationId=res-789');
    expect(extractIntent(params)).toEqual({
      referralId: undefined,
      shelterId: undefined,
      reservationId: 'res-789',
    });
  });

  it('extracts multiple params simultaneously (rare but possible)', () => {
    const params = new URLSearchParams('referralId=ref-1&shelterId=sh-2');
    expect(extractIntent(params)).toEqual({
      referralId: 'ref-1',
      shelterId: 'sh-2',
      reservationId: undefined,
    });
  });
});

describe('intentsEqual', () => {
  it('treats both undefined as equal', () => {
    expect(intentsEqual(undefined, undefined)).toBe(true);
  });

  it('treats one undefined as unequal', () => {
    expect(intentsEqual({ referralId: 'x' }, undefined)).toBe(false);
    expect(intentsEqual(undefined, { referralId: 'x' })).toBe(false);
  });

  it('compares all three id fields', () => {
    expect(intentsEqual({ referralId: 'r' }, { referralId: 'r' })).toBe(true);
    expect(intentsEqual({ referralId: 'r' }, { referralId: 's' })).toBe(false);
    expect(intentsEqual({ shelterId: 'a' }, { shelterId: 'a' })).toBe(true);
    expect(intentsEqual({ shelterId: 'a' }, { shelterId: 'b' })).toBe(false);
    expect(intentsEqual({ reservationId: 'x' }, { reservationId: 'x' })).toBe(true);
  });

  it('referralId on one side and shelterId on the other are unequal', () => {
    expect(intentsEqual({ referralId: 'r' }, { shelterId: 'r' })).toBe(false);
  });
});

describe('currentIntent', () => {
  it('returns undefined for idle', () => {
    expect(currentIntent({ kind: 'idle' } as DeepLinkState<FakeDetail>)).toBeUndefined();
  });

  it('returns the in-flight intent for resolving', () => {
    expect(currentIntent({ kind: 'resolving', intent: { referralId: 'r' } } as DeepLinkState<FakeDetail>))
      .toEqual({ referralId: 'r' });
  });

  it('returns the resolved intent for awaiting-confirm / expanding / awaiting-target / done', () => {
    const r = resolved('r');
    expect(currentIntent({ kind: 'awaiting-confirm', resolved: r } as DeepLinkState<FakeDetail>)).toEqual({ referralId: 'r' });
    expect(currentIntent({ kind: 'expanding', resolved: r } as DeepLinkState<FakeDetail>)).toEqual({ referralId: 'r' });
    expect(currentIntent({ kind: 'awaiting-target', resolved: r, deadlineAt: 0 } as DeepLinkState<FakeDetail>)).toEqual({ referralId: 'r' });
    expect(currentIntent({ kind: 'done', resolved: r } as DeepLinkState<FakeDetail>)).toEqual({ referralId: 'r' });
  });

  it('returns the stale intent for stale', () => {
    expect(currentIntent({ kind: 'stale', intent: { referralId: 'r' }, reason: 'timeout' } as DeepLinkState<FakeDetail>))
      .toEqual({ referralId: 'r' });
  });
});

describe('deepLinkReducer — D-12 state machine', () => {
  function reduce(state: DeepLinkState<FakeDetail>, action: DeepLinkAction<FakeDetail>): DeepLinkState<FakeDetail> {
    return deepLinkReducer<FakeDetail>(state, action);
  }

  it('idle + INTENT → resolving', () => {
    const s = reduce({ kind: 'idle' }, { type: 'INTENT', intent: { referralId: 'r' } });
    expect(s).toEqual({ kind: 'resolving', intent: { referralId: 'r' } });
  });

  it('resolving + RESOLVED (no confirm) → expanding', () => {
    const start: DeepLinkState<FakeDetail> = { kind: 'resolving', intent: { referralId: 'r' } };
    const r = resolved('r');
    const s = reduce(start, { type: 'RESOLVED', resolved: r, needsConfirm: false });
    expect(s).toEqual({ kind: 'expanding', resolved: r });
  });

  it('resolving + RESOLVED (needs confirm) → awaiting-confirm', () => {
    const start: DeepLinkState<FakeDetail> = { kind: 'resolving', intent: { referralId: 'r' } };
    const r = resolved('r');
    const s = reduce(start, { type: 'RESOLVED', resolved: r, needsConfirm: true });
    expect(s).toEqual({ kind: 'awaiting-confirm', resolved: r });
  });

  it('resolving + RESOLVED for a DIFFERENT intent → ignored (no transition)', () => {
    // Late RESOLVED from a superseded resolve must not steal the new intent.
    const start: DeepLinkState<FakeDetail> = { kind: 'resolving', intent: { referralId: 'NEW' } };
    const stale = resolved('OLD');
    const s = reduce(start, { type: 'RESOLVED', resolved: stale, needsConfirm: false });
    expect(s).toEqual(start);
  });

  it('awaiting-confirm + CONFIRM_CONTINUE → expanding', () => {
    const r = resolved('r');
    const s = reduce({ kind: 'awaiting-confirm', resolved: r }, { type: 'CONFIRM_CONTINUE' });
    expect(s).toEqual({ kind: 'expanding', resolved: r });
  });

  it('awaiting-confirm + CONFIRM_ABORT → idle', () => {
    const r = resolved('r');
    const s = reduce({ kind: 'awaiting-confirm', resolved: r }, { type: 'CONFIRM_ABORT' });
    expect(s).toEqual({ kind: 'idle' });
  });

  it('expanding + EXPAND_DONE → awaiting-target with deadline', () => {
    const r = resolved('r');
    const s = reduce({ kind: 'expanding', resolved: r }, { type: 'EXPAND_DONE', deadlineAt: 1000 });
    expect(s).toEqual({ kind: 'awaiting-target', resolved: r, deadlineAt: 1000 });
  });

  it('awaiting-target + TARGET_READY → done', () => {
    const r = resolved('r');
    const s = reduce(
      { kind: 'awaiting-target', resolved: r, deadlineAt: 1000 },
      { type: 'TARGET_READY' },
    );
    expect(s).toEqual({ kind: 'done', resolved: r });
  });

  it('STALE from any non-idle state with matching intent → stale', () => {
    const r = resolved('r');
    expect(reduce(
      { kind: 'resolving', intent: { referralId: 'r' } },
      { type: 'STALE', intent: { referralId: 'r' }, reason: 'not-found' },
    )).toEqual({ kind: 'stale', intent: { referralId: 'r' }, reason: 'not-found' });

    expect(reduce(
      { kind: 'expanding', resolved: r },
      { type: 'STALE', intent: { referralId: 'r' }, reason: 'error' },
    )).toEqual({ kind: 'stale', intent: { referralId: 'r' }, reason: 'error' });

    expect(reduce(
      { kind: 'awaiting-target', resolved: r, deadlineAt: 0 },
      { type: 'STALE', intent: { referralId: 'r' }, reason: 'timeout' },
    )).toEqual({ kind: 'stale', intent: { referralId: 'r' }, reason: 'timeout' });
  });

  it('STALE from idle → ignored (no spurious stale)', () => {
    const s = reduce({ kind: 'idle' }, { type: 'STALE', intent: { referralId: 'r' }, reason: 'timeout' });
    expect(s).toEqual({ kind: 'idle' });
  });

  it('STALE for a non-current intent → ignored (late timeout from superseded request)', () => {
    const start: DeepLinkState<FakeDetail> = { kind: 'resolving', intent: { referralId: 'NEW' } };
    const s = reduce(start, { type: 'STALE', intent: { referralId: 'OLD' }, reason: 'timeout' });
    expect(s).toEqual(start);
  });

  it('RESET → idle from any state', () => {
    const r = resolved('r');
    expect(reduce({ kind: 'awaiting-target', resolved: r, deadlineAt: 0 }, { type: 'RESET' }))
      .toEqual({ kind: 'idle' });
    expect(reduce({ kind: 'done', resolved: r }, { type: 'RESET' }))
      .toEqual({ kind: 'idle' });
    expect(reduce({ kind: 'stale', intent: { referralId: 'r' }, reason: 'error' }, { type: 'RESET' }))
      .toEqual({ kind: 'idle' });
  });

  it('INTENT replaces any current state (URL change supersedes prior deep-link)', () => {
    const r = resolved('OLD');
    const s = reduce(
      { kind: 'awaiting-target', resolved: r, deadlineAt: 0 },
      { type: 'INTENT', intent: { referralId: 'NEW' } },
    );
    expect(s).toEqual({ kind: 'resolving', intent: { referralId: 'NEW' } });
  });

  it('CONFIRM_CONTINUE outside awaiting-confirm → ignored (defensive)', () => {
    const r = resolved('r');
    const start: DeepLinkState<FakeDetail> = { kind: 'expanding', resolved: r };
    expect(reduce(start, { type: 'CONFIRM_CONTINUE' })).toEqual(start);
  });

  it('TARGET_READY outside awaiting-target → ignored (defensive)', () => {
    const r = resolved('r');
    const start: DeepLinkState<FakeDetail> = { kind: 'expanding', resolved: r };
    expect(reduce(start, { type: 'TARGET_READY' })).toEqual(start);
  });
});
