import { describe, it, expect } from 'vitest';
import { classifyDeepLinkOutcome } from './notificationDeepLinkMetrics';

/**
 * Pure-helper tests for the outcome classifier. The network-layer POST
 * is intentionally not tested here — it's a thin fire-and-forget wrapper
 * over api.post, covered end-to-end by Phase 4 Playwright 11.x.
 */

describe('classifyDeepLinkOutcome', () => {
  it('returns success for kind=done regardless of other signals', () => {
    expect(classifyDeepLinkOutcome('done', undefined, false)).toBe('success');
    // Even if the browser reports offline at the moment the hook reached
    // done (rare but possible in a flaky connection), success is the
    // right tag — the deep-link actually landed.
    expect(classifyDeepLinkOutcome('done', undefined, true)).toBe('success');
  });

  it('returns offline for stale+error+offline — the only path that tags offline', () => {
    expect(classifyDeepLinkOutcome('stale', 'error', true)).toBe('offline');
  });

  it('returns stale for stale+error when browser is online', () => {
    expect(classifyDeepLinkOutcome('stale', 'error', false)).toBe('stale');
  });

  it('returns stale for stale+not-found regardless of online state', () => {
    // not-found is a 404/403 — server said the target is gone. Even if the
    // browser later goes offline, the classification is stale (domain state),
    // not offline (connection state).
    expect(classifyDeepLinkOutcome('stale', 'not-found', false)).toBe('stale');
    expect(classifyDeepLinkOutcome('stale', 'not-found', true)).toBe('stale');
  });

  it('returns stale for stale+timeout regardless of online state', () => {
    // timeout is the awaiting-target deadline firing — the target never
    // materialized. Server responded fine (otherwise it would be error);
    // classify as stale not offline.
    expect(classifyDeepLinkOutcome('stale', 'timeout', false)).toBe('stale');
    expect(classifyDeepLinkOutcome('stale', 'timeout', true)).toBe('stale');
  });

  it('returns stale for stale+race', () => {
    expect(classifyDeepLinkOutcome('stale', 'race', false)).toBe('stale');
  });

  it('returns stale when staleReason is undefined (defensive)', () => {
    // Shouldn't happen — stale state always carries a reason — but if the
    // state machine's shape ever drifts, default to 'stale' tag.
    expect(classifyDeepLinkOutcome('stale', undefined, false)).toBe('stale');
    expect(classifyDeepLinkOutcome('stale', undefined, true)).toBe('stale');
  });
});
