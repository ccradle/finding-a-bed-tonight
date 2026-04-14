import { useState, useEffect } from 'react';
import { FormattedMessage } from 'react-intl';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import { SSE_REFERRAL_UPDATE } from '../hooks/useNotifications';

/**
 * Source of the referralId the banner is forwarding to its click handler.
 *
 * <p>{@code 'url'} — the user arrived via a notification deep-link that put
 * {@code ?referralId=X} in the URL; that value was captured by the parent
 * and passed to the banner as the {@code referralId} prop. The banner
 * forwards it unchanged on click.</p>
 *
 * <p>{@code 'hint'} — the URL has no {@code ?referralId} param, but the
 * banner's count-endpoint response carried a {@code firstPending} routing
 * hint pointing at the oldest pending referral across the caller's assigned
 * shelters (notification-deep-linking Section 16 / design decision D-BP).
 * The banner forwards that hint on click so the dashboard can deep-link
 * to the specific referral's shelter — closing the genesis gap where the
 * click fallback used to land the user at the alphabetically-first DV
 * shelter regardless of where the pending referral actually lived.</p>
 */
export type BannerClickSource = 'url' | 'hint';

/**
 * Payload forwarded to {@link CoordinatorReferralBannerProps#onBannerClick}.
 * {@code referralId} is the UUID the dashboard will navigate to via
 * {@code setSearchParams({ referralId })}. {@code source} is for
 * observability and routing-precedence decisions at the call site — the
 * dashboard's click handler short-circuits when {@code source === 'url'}
 * (re-clicking an already-deep-linked URL adds no information) and
 * navigates when {@code source === 'hint'}.
 */
export interface BannerClickTarget {
  source: BannerClickSource;
  referralId: string;
}

/**
 * Pure function — resolve the banner's click target from the inputs the
 * banner has at render time. Exported so Vitest can exercise all three
 * branches (URL, hint, null) without mounting the full component (task
 * 16.4.x). Pre-condition: banner is only rendered when {@code count > 0},
 * so in practice either a URL referralId OR a firstPending hint is
 * expected to be present.
 *
 * @param referralId  from {@code ?referralId=X} in the URL, if any
 * @param firstPending  the routing hint from {@code GET /pending/count}
 * @returns target or {@code null} if no actionable identifier is available
 */
export function computeBannerClickTarget(
  referralId: string | undefined,
  firstPending: { referralId: string } | null,
): BannerClickTarget | null {
  if (referralId) return { source: 'url', referralId };
  if (firstPending) return { source: 'hint', referralId: firstPending.referralId };
  return null;
}

interface CoordinatorReferralBannerProps {
  /**
   * Callback fired when the banner is clicked, or {@code null} if no
   * actionable target is available (which should not happen in practice —
   * the banner only renders when {@code count > 0}, so either a URL param
   * or a {@code firstPending} hint must be present).
   *
   * <p>The banner prefers the URL {@code referralId} prop over the hint when
   * both are present — the user's own deep-link takes precedence over the
   * server-suggested oldest-pending. See D-BP in design.md.</p>
   */
  onBannerClick?: (target: BannerClickTarget | null) => void;
  /**
   * When present, the referralId captured from the URL search params by the
   * parent. Passed through {@code onBannerClick} as {@code source='url'}.
   */
  referralId?: string;
}

/**
 * Persistent red banner showing pending DV referral count for coordinators (T-43).
 *
 * - Fetches count from GET /api/v1/dv-referrals/pending/count on mount (Design D7)
 * - NOT dismissable — resolves only when referrals are actioned (T-45)
 * - Updates in real-time via SSE referral update events (T-48)
 * - WCAG: role="alert" for screen reader announcement, min 44px touch target (T-49)
 * - Dark mode: uses color tokens (errorBg, textInverse) for both themes
 * - notification-deep-linking (Issue #106):
 *   - When {@code ?referralId=X} is in the URL, forwards that on click.
 *   - Otherwise, forwards the count-endpoint's {@code firstPending} routing
 *     hint — points at the oldest pending referral across the caller's
 *     assigned shelters. Closes the banner genesis gap (Section 16 / D-BP).
 */
export function CoordinatorReferralBanner({ onBannerClick, referralId }: CoordinatorReferralBannerProps) {
  const [pendingCount, setPendingCount] = useState(0);
  // Only store what we forward. The backend also returns {@code shelterId}
  // in the {@code firstPending} object, but the banner's contract with
  // {@link CoordinatorDashboard} is to forward a referralId — the dashboard
  // resolves the shelter via {@code useDeepLink}'s resolveTarget. Storing
  // shelterId here would be dead weight (Alex, Section 16 warroom L-1).
  const [firstPending, setFirstPending] = useState<{ referralId: string } | null>(null);

  const fetchCount = () => {
    api.get<{ count: number; firstPending: { referralId: string; shelterId: string } | null }>(
      '/api/v1/dv-referrals/pending/count',
    )
      .then((data) => {
        setPendingCount(data.count);
        // M-1 defensive coercion (Marcus + Sam, Section 16 warroom): if a
        // pre-Section-16 backend is still deployed (rollback / partial
        // deploy), {@code data.firstPending} is {@code undefined} rather
        // than {@code null}. Normalize to null at the state boundary so
        // the rest of the component can rely on the {@code { ... } | null}
        // invariant and the click handler's falsy-branch fallback behaves
        // sensibly (no-op rather than crash on undefined access).
        setFirstPending(data.firstPending
            ? { referralId: data.firstPending.referralId }
            : null);
      })
      .catch(() => {}); // Silent — banner just doesn't show
  };

  // Fetch on mount
  useEffect(() => { fetchCount(); }, []);

  // T-48: Re-fetch on SSE referral update events (real-time banner update)
  useEffect(() => {
    const handler = () => fetchCount();
    window.addEventListener(SSE_REFERRAL_UPDATE, handler);
    return () => window.removeEventListener(SSE_REFERRAL_UPDATE, handler);
  }, []);

  if (pendingCount <= 0) return null;

  const target = computeBannerClickTarget(referralId, firstPending);

  return (
    <div
      role="alert"
      data-testid="coordinator-referral-banner"
      onClick={() => onBannerClick?.(target)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onBannerClick?.(target); }}
      tabIndex={onBannerClick ? 0 : undefined}
      style={{
        backgroundColor: color.error,
        color: color.textInverse,
        padding: '12px 20px',
        fontSize: text.base,
        fontWeight: weight.semibold,
        textAlign: 'center',
        cursor: onBannerClick ? 'pointer' : 'default',
        minHeight: '44px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        borderRadius: 0, // Full-width banner, no rounding
      }}
    >
      <FormattedMessage
        id="coordinator.referralBanner"
        values={{ count: pendingCount }}
      />
    </div>
  );
}
