import { FormattedMessage } from 'react-intl';
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
 */
export function CriticalNotificationBanner({ notifications }: CriticalNotificationBannerProps) {
  const criticalUnread = notifications.filter(
    (n) => !n.read && n.data?.severity === 'CRITICAL'
  );

  if (criticalUnread.length === 0) return null;

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
      }}
    >
      <FormattedMessage
        id="notifications.criticalBanner"
        values={{ count: criticalUnread.length }}
      />
    </div>
  );
}
