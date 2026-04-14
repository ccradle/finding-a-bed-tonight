import { useState, useRef, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import { useAuth } from '../auth/useAuth';
import type { Notification } from '../hooks/useNotifications';
import {
  getNotificationMessageId,
  getNotificationMessageValues,
  getNavigationPath,
} from './notificationMessages';
import {
  deriveLifecycleState,
  stateLabelIdFor,
  stateTooltipIdFor,
  stateInlineLabelIdFor,
} from './notificationLifecycle';

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
  // Phase 3 task 7.5 — hide-acted filter. Default OFF per M-2 (first-
  // time users see all states to learn the system). Preference persists
  // to localStorage so a coordinator who toggles it once has it remembered
  // across sessions. SSR-safe — reads localStorage only when window exists.
  const [hideActed, setHideActed] = useState<boolean>(() => {
    if (typeof window === 'undefined') return false;
    return window.localStorage.getItem('fabt_notif_hide_acted') === 'true';
  });
  const toggleHideActed = useCallback(() => {
    setHideActed((prev) => {
      const next = !prev;
      if (typeof window !== 'undefined') {
        window.localStorage.setItem('fabt_notif_hide_acted', String(next));
      }
      return next;
    });
  }, []);
  // Apply the filter to the list the render walks. Badge count stays
  // unread-only regardless (task 7.6 — the badge reads `unreadCount` which
  // the hook already derives from REST baseline + SSE delta, both counting
  // unread only).
  const visibleNotifications = hideActed
    ? notifications.filter((n) => !n.acted)
    : notifications;
  // User roles drive role-aware deep-link destinations per notification-deep-linking
  // (Issue #106). Admins land on the escalation queue, coordinators on the specific
  // referral in their dashboard, outreach workers on My Past Holds.
  const { user } = useAuth();
  const userRoles = user?.roles ?? [];

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
            <div style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
              {/* Phase 3 task 7.5 — hide-acted toggle. Small text button
                  matches markAllRead styling. aria-pressed signals the
                  toggle state to screen readers; title= gives the tooltip
                  explanation per 7.4a convention. */}
              <button
                onClick={(e) => { e.stopPropagation(); toggleHideActed(); }}
                aria-pressed={hideActed}
                data-testid="notifications-hide-acted-toggle"
                title={intl.formatMessage({ id: 'notifications.hideActed.tooltip' })}
                style={{
                  background: hideActed ? color.bgHighlight : 'none',
                  border: 'none',
                  color: hideActed ? color.primaryText : color.textTertiary,
                  fontSize: text.xs,
                  cursor: 'pointer',
                  padding: '4px 8px',
                  minHeight: '44px',
                  borderRadius: 4,
                  display: 'flex',
                  alignItems: 'center',
                  fontWeight: hideActed ? weight.semibold : weight.normal,
                }}
              >
                <FormattedMessage id={hideActed ? 'notifications.hideActed.on' : 'notifications.hideActed.off'} />
              </button>
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
          </div>

          {visibleNotifications.length === 0 ? (
            <div style={{
              padding: '24px 16px',
              textAlign: 'center',
              color: color.textMuted,
              fontSize: text.sm,
            }}>
              <FormattedMessage
                id={hideActed && notifications.length > 0
                  ? 'notifications.emptyFiltered'
                  : 'notifications.empty'}
              />
            </div>
          ) : (
            <ul role="list" style={{ listStyle: 'none', margin: 0, padding: 0 }}>
              {visibleNotifications.map((notification) => {
                // Phase 3 task 7.4 — derive the lifecycle state via the pure
                // helper so all three render branches (body style, aria-label,
                // tooltip) stay in sync and the mapping is unit-testable
                // (war-room M-1 extraction).
                const lifecycleState = deriveLifecycleState(notification);
                const bodyText = intl.formatMessage(
                  { id: getNotificationMessageId(notification) },
                  getNotificationMessageValues(notification, intl),
                );
                // T-2 — aria-label includes the state word; 7.4a tooltip is
                // the native-tooltip hover/focus explanation.
                const stateLabel = intl.formatMessage({ id: stateLabelIdFor(lifecycleState) });
                const stateTooltipId = stateTooltipIdFor(lifecycleState);
                const inlineLabelId = stateInlineLabelIdFor(lifecycleState);
                return (
                <li
                  key={notification.id}
                  tabIndex={0}
                  aria-label={`${bodyText}. ${stateLabel}.`}
                  onClick={() => {
                    onMarkRead(notification.id);
                    setOpen(false);
                    navigate(getNavigationPath(notification, userRoles));
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      onMarkRead(notification.id);
                      setOpen(false);
                      navigate(getNavigationPath(notification, userRoles));
                    }
                  }}
                  style={{
                    padding: '12px 16px',
                    borderBottom: `1px solid ${color.bgTertiary}`,
                    cursor: 'pointer',
                    // Three visual states per D7:
                    // unread → highlight background, semibold text, counter++
                    // pending (read-unacted) → normal background, normal weight, "• Pending" indicator
                    // acted → normal background, muted text, ✓ indicator
                    backgroundColor: lifecycleState === 'unread' ? color.bgHighlight : color.bg,
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                  }}
                >
                  <div style={{ flex: 1 }}>
                    <div style={{
                      fontSize: text.sm,
                      color: lifecycleState === 'acted' ? color.textMuted : color.text,
                      fontWeight: lifecycleState === 'unread' ? weight.semibold : weight.normal,
                    }}>
                      <FormattedMessage
                        id={getNotificationMessageId(notification)}
                        values={getNotificationMessageValues(notification, intl)}
                      />
                    </div>
                    {notification.eventType === 'availability.updated' && (() => {
                      // Use the helper so persistent notifications (where
                      // shelterName is inside data.payload JSON string)
                      // render it too — same bug class as referral.responded
                      // fixed in v0.32.3.
                      const shelterName = getNotificationMessageValues(notification).shelterName;
                      return shelterName ? (
                        <div style={{ fontSize: text.xs, color: color.textMuted, marginTop: '2px' }}>
                          {shelterName}
                        </div>
                      ) : null;
                    })()}
                    {/* State indicator: '• Pending' for read-unacted, '✓
                        Completed' for acted, hidden for unread (the bg
                        highlight + badge already convey unread). title= is
                        the native-tooltip hover/focus explanation (task 7.4a).
                        inlineLabelId is null when lifecycleState is 'unread'. */}
                    {inlineLabelId && (
                      <div
                        data-testid={`notification-state-${notification.id}`}
                        title={intl.formatMessage({ id: stateTooltipId })}
                        style={{
                          fontSize: text['2xs'],
                          color: color.textMuted,
                          marginTop: 2,
                          display: 'flex',
                          alignItems: 'center',
                          gap: 4,
                        }}
                      >
                        {lifecycleState === 'acted' ? '✓' : '•'}
                        <FormattedMessage id={inlineLabelId} />
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
                );
              })}
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
