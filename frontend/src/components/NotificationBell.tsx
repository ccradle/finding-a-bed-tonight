import { useState, useRef, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import type { Notification } from '../hooks/useNotifications';

interface NotificationBellProps {
  notifications: Notification[];
  unreadCount: number;
  onMarkRead: (id: string) => void;
  onMarkAllRead: () => void;
  onDismiss: (id: string) => void;
  onLoadMore?: () => void;
  hasMore?: boolean;
  loadingMore?: boolean;
}

function getNotificationMessageId(notification: Notification): string {
  const { eventType, data } = notification;
  switch (eventType) {
    case 'dv-referral.responded':
    case 'referral.responded':
      return data.status === 'ACCEPTED'
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

function getNotificationMessageValues(notification: Notification): Record<string, string> {
  const { data } = notification;

  // Persistent notifications (type "notification" from SSE) have payload as a JSON string.
  // Domain events (dv-referral.responded, etc.) have data fields directly.
  let payload: Record<string, unknown> = {};
  if (typeof data.payload === 'string') {
    try { payload = JSON.parse(data.payload); } catch { /* malformed payload */ }
  }

  return {
    shelterName: String(data.shelterName || payload.shelterName || ''),
    status: String(data.status || payload.status || ''),
    count: String(data.count || ''),
  };
}

function getNavigationPath(eventType: string): string {
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

const PANEL_ID = 'notification-panel';

export function NotificationBell({
  notifications,
  unreadCount,
  onMarkRead,
  onMarkAllRead,
  onDismiss,
  onLoadMore,
  hasMore,
  loadingMore,
}: NotificationBellProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const intl = useIntl();
  const navigate = useNavigate();

  const closePanel = useCallback(() => {
    setOpen(false);
    buttonRef.current?.focus();
  }, []);

  // Close on outside click
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
      return () => document.removeEventListener('mousedown', handleClickOutside);
    }
  }, [open]);

  // Focus first item on open
  useEffect(() => {
    if (open && panelRef.current) {
      const firstFocusable = panelRef.current.querySelector<HTMLElement>('[tabindex="0"], a, button');
      firstFocusable?.focus();
    }
  }, [open]);

  // Escape to close — return focus to bell button
  useEffect(() => {
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && open) {
        closePanel();
      }
    }
    if (open) {
      document.addEventListener('keydown', handleKeyDown);
      return () => document.removeEventListener('keydown', handleKeyDown);
    }
  }, [open, closePanel]);

  const bellLabel = unreadCount > 0
    ? intl.formatMessage({ id: 'notifications.bellWithCount' }, { count: unreadCount })
    : intl.formatMessage({ id: 'notifications.bell' });

  return (
    <div ref={containerRef} style={{ position: 'relative' }} data-testid="notification-bell">
      {/* WCAG: aria-live region pre-rendered empty on page load */}
      <div aria-live="polite" aria-atomic="true" style={{
        position: 'absolute', width: '1px', height: '1px', padding: 0,
        margin: '-1px', overflow: 'hidden', clip: 'rect(0,0,0,0)',
        whiteSpace: 'nowrap', border: 0,
      }}>
        {unreadCount > 0
          ? intl.formatMessage({ id: 'notifications.screenReaderCount' }, { count: unreadCount })
          : ''}
      </div>

      <button
        ref={buttonRef}
        onClick={() => { setOpen(!open); }}
        aria-label={bellLabel}
        aria-expanded={open}
        aria-controls={PANEL_ID}
        data-testid="notification-bell-button"
        style={{
          position: 'relative',
          padding: '8px',
          backgroundColor: 'transparent',
          color: color.textInverse,
          border: '1px solid rgba(255,255,255,0.3)',
          borderRadius: '6px',
          cursor: 'pointer',
          fontSize: text.base,
          minHeight: '44px',
          minWidth: '44px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {/* Bell icon (SVG) */}
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" />
          <path d="M13.73 21a2 2 0 0 1-3.46 0" />
        </svg>
        {/* Badge — aria-hidden since count is in aria-label */}
        {unreadCount > 0 && (
          <span
            aria-hidden="true"
            style={{
              position: 'absolute',
              top: '2px',
              right: '2px',
              backgroundColor: color.errorMid,
              color: color.textInverse,
              borderRadius: '50%',
              minWidth: '18px',
              height: '18px',
              fontSize: text['2xs'],
              fontWeight: weight.bold,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              padding: '0 4px',
              lineHeight: 1,
            }}
          >
            {unreadCount > 9 ? '9+' : unreadCount}
          </span>
        )}
      </button>

      {/* Disclosure panel — WAI-ARIA disclosure pattern (not menu) */}
      {open && (
        <div
          ref={panelRef}
          id={PANEL_ID}
          role="region"
          aria-label={intl.formatMessage({ id: 'notifications.title' })}
          data-testid="notification-panel"
          style={{
            position: 'absolute',
            top: '100%',
            right: 0,
            marginTop: '8px',
            width: '320px',
            maxHeight: '400px',
            overflowY: 'auto',
            backgroundColor: color.bg,
            borderRadius: '8px',
            boxShadow: '0 4px 20px rgba(0,0,0,0.15)',
            zIndex: 1000,
          }}
        >
          <div style={{
            padding: '12px 16px',
            borderBottom: `1px solid ${color.border}`,
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}>
            <span style={{ fontWeight: weight.semibold, color: color.text, fontSize: text.sm }}>
              <FormattedMessage id="notifications.title" />
            </span>
            {unreadCount > 0 && (
              <button
                onClick={(e) => { e.stopPropagation(); onMarkAllRead(); }}
                data-testid="mark-all-read-button"
                style={{
                  background: 'none',
                  border: 'none',
                  color: color.primaryText,
                  fontSize: text.xs,
                  cursor: 'pointer',
                  padding: '4px 8px',
                  minHeight: '44px',
                  display: 'flex',
                  alignItems: 'center',
                }}
              >
                <FormattedMessage id="notifications.markAllRead" />
              </button>
            )}
          </div>

          {notifications.length === 0 ? (
            <div style={{
              padding: '24px 16px',
              textAlign: 'center',
              color: color.textMuted,
              fontSize: text.sm,
            }}>
              <FormattedMessage id="notifications.empty" />
            </div>
          ) : (
            <ul role="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {notifications.map((notification) => (
                <li
                  key={notification.id}
                  tabIndex={0}
                  onClick={() => {
                    onMarkRead(notification.id);
                    setOpen(false);
                    navigate(getNavigationPath(notification.eventType));
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onMarkRead(notification.id);
                      setOpen(false);
                      navigate(getNavigationPath(notification.eventType));
                    }
                  }}
                  style={{
                    padding: '12px 16px',
                    borderBottom: `1px solid ${color.bgTertiary}`,
                    cursor: 'pointer',
                    backgroundColor: notification.read ? color.bg : color.bgHighlight,
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                  }}
                >
                  <div style={{ flex: 1 }}>
                    <div style={{
                      fontSize: text.sm,
                      color: color.text,
                      fontWeight: notification.read ? weight.normal : weight.semibold,
                    }}>
                      <FormattedMessage
                        id={getNotificationMessageId(notification)}
                        values={getNotificationMessageValues(notification)}
                      />
                    </div>
                    {notification.data.shelterName != null && notification.eventType === 'availability.updated' && (
                      <div style={{ fontSize: text.xs, color: color.textMuted, marginTop: '2px' }}>
                        {String(notification.data.shelterName)}
                      </div>
                    )}
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); onDismiss(notification.id); }}
                    aria-label={intl.formatMessage({ id: 'notifications.dismiss' })}
                    style={{
                      background: 'none',
                      border: 'none',
                      color: color.textMuted,
                      cursor: 'pointer',
                      padding: '4px',
                      fontSize: text.md,
                      lineHeight: 1,
                      minHeight: '44px',
                      minWidth: '44px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
          {hasMore && onLoadMore && (
            <button
              type="button"
              onClick={(e) => { e.stopPropagation(); onLoadMore(); }}
              disabled={loadingMore}
              data-testid="notification-load-more"
              style={{
                width: '100%',
                padding: '10px',
                backgroundColor: color.bgSecondary,
                color: loadingMore ? color.textMuted : color.primaryText,
                border: 'none',
                borderTop: `1px solid ${color.border}`,
                cursor: loadingMore ? 'wait' : 'pointer',
                fontSize: text.sm,
                fontWeight: weight.medium,
                borderRadius: '0 0 8px 8px',
              }}
            >
              {loadingMore ? '...' : <FormattedMessage id="notifications.loadMore" />}
            </button>
          )}
        </div>
      )}
    </div>
  );
}
