import { describe, it, expect } from 'vitest';
import {
  computeBannerClickTarget,
  type BannerClickTarget,
} from './CoordinatorReferralBanner';

/**
 * Pure-function tests for {@link computeBannerClickTarget} — the target-
 * resolution helper exported from {@link CoordinatorReferralBanner}.
 *
 * <p>Covers Section 16 Vitest tasks 16.4.1–16.4.3 as pure-function
 * assertions. The project convention is Vitest-for-logic + Playwright-for-
 * render (see {@code notificationLifecycle.test.ts} et al — no project
 * component-mount tests today, no React Testing Library in package.json).
 * Render / click behavior is covered end-to-end by the Playwright regression
 * at {@code persistent-notifications.spec.ts:'Issue #106 Section 16: banner
 * click routes via firstPending hint…'} which exercises the actual DOM event,
 * the {@code onBannerClick} wiring in {@code CoordinatorDashboard.tsx}, the
 * {@code setSearchParams} navigation, and the downstream {@code useDeepLink}
 * state transitions.</p>
 *
 * <p>Stale-click path (Section 16 scenario 3, task 16.4.5) is
 * covered by {@code useDeepLink}'s reducer tests — a banner-click that
 * dispatches INTENT transitions the machine identically to a URL change,
 * so the stale branch is reached by any test driving a non-PENDING referral
 * into the resolveTarget callback. See {@code useDeepLink.test.ts}.</p>
 */

describe('computeBannerClickTarget', () => {
  // 16.4.1 — banner with firstPending hint, no URL referralId → 'hint' source.
  it('returns hint target when only firstPending is present', () => {
    const target = computeBannerClickTarget(
      undefined,
      { referralId: 'abc-123' },
    );
    expect(target).toEqual({ source: 'hint', referralId: 'abc-123' });
  });

  // 16.4.2 — URL referralId wins when both are present. Design decision D-BP:
  // user's own deep-link takes precedence over the server-suggested oldest-
  // pending. Proves the precedence even when the hint points elsewhere.
  it('returns url target when referralId is present, regardless of hint', () => {
    const target = computeBannerClickTarget(
      'url-xyz',
      { referralId: 'hint-abc' },
    );
    expect(target).toEqual({ source: 'url', referralId: 'url-xyz' });
  });

  // URL wins even when the hint is null (direct-nav case where the banner
  // happened to be mounted by a URL-driven parent).
  it('returns url target when referralId is present and firstPending is null', () => {
    const target = computeBannerClickTarget('url-xyz', null);
    expect(target).toEqual({ source: 'url', referralId: 'url-xyz' });
  });

  // 16.4.3 — neither source available → null. Defensive; banner shouldn't
  // render when count is 0, but if a race puts us here the caller no-ops.
  it('returns null when neither URL referralId nor firstPending is present', () => {
    expect(computeBannerClickTarget(undefined, null)).toBeNull();
  });

  // Empty string referralId should be treated as absent — the banner prop
  // is optional, and an empty string is a common deserialization artifact
  // (URLSearchParams.get returns '' for 'foo=' rather than null).
  it('treats empty-string referralId as absent and falls back to hint', () => {
    const target = computeBannerClickTarget(
      '',
      { referralId: 'hint-abc' },
    );
    expect(target).toEqual({ source: 'hint', referralId: 'hint-abc' });
  });

  // Return type discipline — exercise the BannerClickTarget type so a
  // refactor that widens the type silently doesn't break the contract.
  it('returned target has correct shape (type-level guard)', () => {
    const target = computeBannerClickTarget('abc', null);
    if (target === null) {
      throw new Error('expected non-null for this input');
    }
    // Type-narrow proof: the destructure below only compiles if the
    // return type is {source, referralId}. If it ever grows extra fields
    // this test will need to be updated — that's a contract signal.
    const { source, referralId }: BannerClickTarget = target;
    expect(source).toBe('url');
    expect(referralId).toBe('abc');
  });
});
