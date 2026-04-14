import { useState, useEffect } from 'react';
import { FormattedMessage } from 'react-intl';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import { SSE_REFERRAL_UPDATE } from '../hooks/useNotifications';

interface CoordinatorReferralBannerProps {
  /**
   * Callback fired when the banner is clicked. Receives the referralId from
   * the notification-deep-linking URL query param ({@code ?referralId=X}) if
   * one is present — the dashboard passes it through so that clicking the
   * banner during an active deep-link opens the specific referral rather
   * than the first DV shelter (task 3.5 of notification-deep-linking).
   */
  onBannerClick?: (referralId?: string) => void;
  /**
   * When present, this is the referralId captured from the URL search params
   * by the parent. Passed back through {@code onBannerClick} so the parent
   * can route the click. The banner itself renders identically either way —
   * deep-link state doesn't change copy or severity, only destination.
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
 * - notification-deep-linking (Issue #106): forwards {@code referralId} to
 *   the click handler so the dashboard can open the specific referral when
 *   the user arrived via a notification deep-link.
 */
export function CoordinatorReferralBanner({ onBannerClick, referralId }: CoordinatorReferralBannerProps) {
  const [pendingCount, setPendingCount] = useState(0);

  const fetchCount = () => {
    api.get<{ count: number }>('/api/v1/dv-referrals/pending/count')
      .then((data) => setPendingCount(data.count))
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

  return (
    <div
      role="alert"
      data-testid="coordinator-referral-banner"
      onClick={() => onBannerClick?.(referralId)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onBannerClick?.(referralId); }}
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
