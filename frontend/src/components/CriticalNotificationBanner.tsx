import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import type { Notification } from '../hooks/useNotifications';

interface CriticalNotificationBannerProps {
  notifications: Notification[];
}

/**
 * Persistent red banner for unread CRITICAL notifications (T-50, Design D3).
 *
 * - Shows at top of page on login if unread CRITICAL notifications exist
 * - NOT a modal — stays visible but does not block page interaction
 * - Coordinators need to navigate to the referral to act on it
 * - role="alert" for screen reader announcement
 * - Uses color.error + color.textInverse for both light and dark mode
 *
 * <p><b>coc-admin-escalation T-40 (Session 6, banner CTA):</b> when one or
 * more of the unread CRITICAL notifications has a {@code type} matching
 * {@code escalation.*}, render an additional primary action button:
 * <i>"Review N pending escalations →"</i>. Click navigates to
 * {@code /admin#dvEscalations}, where {@code AdminPanel.tsx} reads the hash
 * (T-41) and pre-selects the DV Escalations tab. The CTA only appears for
 * escalation-typed CRITICAL notifications — non-escalation CRITICALs (e.g.
 * surge.activated) do NOT show a CTA, because the destination is unclear
 * for those.</p>
 *
 * <p><b>Banner is hidden when N=0:</b> the existing early-return on
 * {@code criticalUnread.length === 0} provides the alert-fatigue
 * discipline (per Lin et al. 2024 CDS study).</p>
 */
export function CriticalNotificationBanner({ notifications }: CriticalNotificationBannerProps) {
  const intl = useIntl();
  const navigate = useNavigate();

  const criticalUnread = notifications.filter(
    (n) => !n.read && n.data?.severity === 'CRITICAL'
  );

  if (criticalUnread.length === 0) return null;

  // CTA gating: only show the "Review pending escalations" button when at
  // least one CRITICAL notification is an escalation. The notification.type
  // field is the source of truth — we DO NOT match on title text or other
  // localization-dependent fields (per the Keisha Thompson + Casey Drummond
  // separation of "system metadata" from "human-facing copy").
  const escalationCriticals = criticalUnread.filter((n) => {
    const type = n.data?.type;
    return typeof type === 'string' && type.startsWith('escalation.');
  });
  const escalationCount = escalationCriticals.length;

  const handleCtaClick = () => {
    // React Router navigate with the hash anchor. AdminPanel reads
    // window.location.hash on mount + hashchange event and pre-selects
    // the matching tab (T-41).
    navigate('/admin#dvEscalations');
  };

  return (
    <div
      role="alert"
      data-testid="critical-notification-banner"
      style={{
        backgroundColor: color.error,
        color: color.textInverse,
        padding: '10px 20px',
        fontSize: text.sm,
        fontWeight: weight.semibold,
        textAlign: 'center',
        minHeight: '44px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        flexWrap: 'wrap',
      }}
    >
      <span>
        <FormattedMessage
          id="notifications.criticalBanner"
          values={{ count: criticalUnread.length }}
        />
      </span>
      {escalationCount > 0 && (
        <button
          type="button"
          onClick={handleCtaClick}
          data-testid="critical-banner-escalation-cta"
          aria-label={intl.formatMessage(
            { id: 'notifications.criticalBanner.cta' },
            { count: escalationCount },
          )}
          style={{
            // 44x44px minimum touch target (WCAG D5)
            minHeight: 44,
            padding: '8px 16px',
            backgroundColor: color.textInverse,
            color: color.error,
            border: 'none',
            borderRadius: 8,
            fontSize: text.sm,
            fontWeight: weight.bold,
            cursor: 'pointer',
            whiteSpace: 'nowrap',
          }}
        >
          <FormattedMessage
            id="notifications.criticalBanner.cta"
            values={{ count: escalationCount }}
          />
        </button>
      )}
    </div>
  );
}
