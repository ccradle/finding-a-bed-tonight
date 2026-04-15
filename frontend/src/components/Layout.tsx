import { type ReactNode, useState, useEffect, useRef, useCallback } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { useAuth } from '../auth/useAuth';
import { getDefaultRouteForRoles } from '../auth/AuthGuard';
import { LocaleSelector } from './LocaleSelector';
import { NotificationBell } from './NotificationBell';
import { QueueStatusIndicator } from './QueueStatusIndicator';
import { ConnectionStatusBanner } from './ConnectionStatusBanner';
import { CriticalNotificationBanner } from './CriticalNotificationBanner';
import { OfflineBanner } from './OfflineBanner';
import { SessionTimeoutWarning } from './SessionTimeoutWarning';
import { ChangePasswordModal } from './ChangePasswordModal';
import { useNotifications } from '../hooks/useNotifications';
import { replayQueue, getQueueSize, isReplaying, type ReplayResult } from '../services/offlineQueue';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

/**
 * Visually hidden styles — content accessible to screen readers but not visible.
 * Used for skip link (visible on focus) and route announcer.
 */
const visuallyHiddenStyle: React.CSSProperties = {
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
};

/**
 * Skip link styles — visible only on focus (keyboard users).
 * WCAG 2.4.1 Bypass Blocks.
 */
const skipLinkStyle: React.CSSProperties = {
  ...visuallyHiddenStyle,
};

interface LayoutProps {
  children: ReactNode;
  locale: string;
  onLocaleChange: (locale: string) => void;
}

interface NavItem {
  path: string;
  labelId: string;
  roles: string[];
}

const NAV_ITEMS: NavItem[] = [
  { path: '/coordinator', labelId: 'nav.shelters', roles: ['COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN'] },
  { path: '/outreach', labelId: 'nav.search', roles: ['OUTREACH_WORKER', 'COC_ADMIN', 'PLATFORM_ADMIN'] },
  // Phase 3 task 6.7: My Past Holds view for outreach workers — hold-
  // cancellation deep-links land here. Role-gated identically to
  // /outreach so admins supporting an outreach worker can follow their
  // view.
  { path: '/outreach/my-holds', labelId: 'nav.myHolds', roles: ['OUTREACH_WORKER', 'COC_ADMIN', 'PLATFORM_ADMIN'] },
  { path: '/admin', labelId: 'nav.admin', roles: ['COC_ADMIN', 'PLATFORM_ADMIN'] },
];

export function Layout({ children, locale, onLocaleChange }: LayoutProps) {
  const { user, logout, expiresIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [isMobile, setIsMobile] = useState(window.innerWidth < 768);

  useEffect(() => {
    const handleResize = () => setIsMobile(window.innerWidth < 768);
    window.addEventListener('resize', handleResize);
    return () => window.removeEventListener('resize', handleResize);
  }, []);

  const visibleNavItems = NAV_ITEMS.filter((item) =>
    user?.roles.some((role) => item.roles.includes(role))
  );

  const intl = useIntl();
  const [changePasswordOpen, setChangePasswordOpen] = useState(false);
  const { notifications, unreadCount, markRead, markAllRead, dismiss, loadMore, hasMore, loadingMore, connected } = useNotifications();
  const [queueSize, setQueueSize] = useState(0);
  const [appVersion, setAppVersion] = useState<string | null>(null);
  const [kebabOpen, setKebabOpen] = useState(false);
  const kebabRef = useRef<HTMLDivElement>(null);
  const kebabButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    api.get<{ version: string }>('/api/v1/version')
      .then(res => setAppVersion(res.version))
      .catch(() => {});
  }, []);

  const refreshQueueSize = useCallback(async () => {
    try {
      const size = await getQueueSize();
      setQueueSize(size);
    } catch { /* IndexedDB unavailable */ }
  }, []);

  // Poll queue size every 2 seconds
  useEffect(() => {
    refreshQueueSize();
    const interval = setInterval(refreshQueueSize, 2000);
    return () => clearInterval(interval);
  }, [refreshQueueSize]);

  // Replay queued actions on reconnect
  useEffect(() => {
    let replayScheduled = false;

    const handleOnline = async () => {
      // Synchronous guard: prevent multiple online events from scheduling parallel replays
      if (replayScheduled) return;
      replayScheduled = true;

      try {
        // Jittered delay to prevent thundering herd when multiple coordinators
        // reconnect simultaneously after a WiFi outage (0-2 seconds)
        const jitterMs = Math.floor(Math.random() * 2000);
        await new Promise(r => setTimeout(r, jitterMs));

        // Double-check: if another mechanism already started replay, skip
        if (isReplaying()) { replayScheduled = false; return; }

        // Signal that replay is starting so components can show SENDING state
        window.dispatchEvent(new CustomEvent('fabt-queue-replaying'));

        const result = await replayQueue();
        refreshQueueSize();

        // Notify components (e.g., OutreachSearch) of replay outcomes
        window.dispatchEvent(new CustomEvent<ReplayResult>('fabt-queue-replayed', { detail: result }));

        const messages: string[] = [];
        if (result.succeeded > 0) {
          messages.push(`${result.succeeded} queued action${result.succeeded > 1 ? 's' : ''} sent`);
        }
        if (result.expired.length > 0) {
          messages.push(`${result.expired.length} hold${result.expired.length > 1 ? 's' : ''} expired while offline`);
        }
        if (result.conflicts.length > 0) {
          messages.push(`${result.conflicts.length} bed${result.conflicts.length > 1 ? 's were' : ' was'} taken while offline`);
        }
        if (result.failed > 0) {
          messages.push(`${result.failed} action${result.failed > 1 ? 's' : ''} failed — will retry`);
        }
        // Show summary as a brief notification if anything happened
        if (messages.length > 0) {
          setReplayMessage(messages.join('. ') + '.');
          setTimeout(() => setReplayMessage(null), 6000);
        }
      } catch { /* replay failed, actions remain in queue for next attempt */ }
      finally { replayScheduled = false; }
    };

    window.addEventListener('online', handleOnline);
    return () => window.removeEventListener('online', handleOnline);
  }, [refreshQueueSize]);

  const [replayMessage, setReplayMessage] = useState<string | null>(null);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handlePasswordChanged = () => {
    setChangePasswordOpen(false);
    logout();
    navigate('/login');
  };

  // Kebab menu: click-outside to close (Design D4)
  useEffect(() => {
    if (!kebabOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (kebabRef.current && !kebabRef.current.contains(e.target as Node)) {
        setKebabOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [kebabOpen]);

  // Kebab menu: Escape to close + return focus (Design D4)
  useEffect(() => {
    if (!kebabOpen) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setKebabOpen(false);
        kebabButtonRef.current?.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [kebabOpen]);

  // Active-nav-item resolution. The previous `pathname.startsWith(path)`
  // implementation double-highlighted: on `/outreach/my-holds` both the
  // "Search Beds" (/outreach) and "My Holds" (/outreach/my-holds) items
  // lit up because `"/outreach/my-holds".startsWith("/outreach")` is true.
  // Reported 2026-04-14 by Corey. Fix: pick the LONGEST nav-item path
  // whose whole-segment matches the current pathname; only that one is
  // active. Also guards against a second latent bug — a hypothetical
  // `/outreachextra` route would false-match `/outreach` under startsWith.
  const activeNavPath = visibleNavItems
    .filter((item) =>
      location.pathname === item.path
      || location.pathname.startsWith(item.path + '/'),
    )
    .sort((a, b) => b.path.length - a.path.length)[0]?.path;
  const isActive = (path: string) => path === activeNavPath;

  // Route announcer — announces page changes to screen readers (WCAG 2.4.3, D2)
  const [routeAnnouncement, setRouteAnnouncement] = useState('');
  const mainRef = useRef<HTMLElement>(null);

  useEffect(() => {
    // Map paths to page titles for screen reader announcement
    const titleMap: Record<string, string> = {
      '/login': 'Sign In',
      '/outreach': 'Search Beds',
      '/coordinator': 'Shelter Dashboard',
      '/admin': 'Administration',
    };
    const title = titleMap[location.pathname] || 'Finding A Bed Tonight';
    setRouteAnnouncement(`Navigated to ${title}`);

    // Move focus to main content on route change
    if (mainRef.current) {
      mainRef.current.focus();
    }
  }, [location.pathname]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      {/* Skip to content link — WCAG 2.4.1 Bypass Blocks (D3) */}
      <a
        href="#main-content"
        style={skipLinkStyle}
        onFocus={(e) => {
          // Make visible on focus
          Object.assign(e.currentTarget.style, {
            position: 'fixed', top: '8px', left: '8px', width: 'auto', height: 'auto',
            clip: 'auto', whiteSpace: 'normal', overflow: 'visible', margin: 0,
            padding: '12px 20px', backgroundColor: color.primary, color: color.headerText,
            borderRadius: '8px', zIndex: '9999', fontSize: text.base, fontWeight: weight.bold,
          });
        }}
        onBlur={(e) => {
          Object.assign(e.currentTarget.style, skipLinkStyle);
        }}
      >
        Skip to main content
      </a>

      {/* Route announcer — screen readers hear page changes (D2) */}
      <div aria-live="polite" aria-atomic="true" style={visuallyHiddenStyle}>
        {routeAnnouncement}
      </div>

      <OfflineBanner />
      <CriticalNotificationBanner notifications={notifications} />
      {replayMessage && (
        <div
          role="status"
          aria-live="polite"
          style={{
            padding: '10px 20px',
            backgroundColor: color.bgHighlight,
            color: color.primaryText,
            fontSize: text.sm,
            fontWeight: weight.medium,
            textAlign: 'center',
            borderBottom: `1px solid ${color.primaryLight}`,
          }}
        >
          {replayMessage}
        </div>
      )}
      <SessionTimeoutWarning expiresIn={expiresIn} onLogout={handleLogout} />
      <ChangePasswordModal
        open={changePasswordOpen}
        onClose={() => setChangePasswordOpen(false)}
        onSuccess={handlePasswordChanged}
      />

      {/* Header */}
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: isMobile ? '8px 12px' : '12px 20px',
          backgroundColor: color.headerBg,
          color: color.headerText,
          minHeight: '56px',
          position: 'relative',
          zIndex: 100,
        }}
      >
        <h1 style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold, whiteSpace: isMobile ? 'nowrap' : undefined }}>
          <a
            href="#"
            onClick={(e) => { e.preventDefault(); navigate(user ? getDefaultRouteForRoles(user.roles) : '/'); }}
            style={{ color: color.headerText, textDecoration: 'none', cursor: 'pointer' }}
            aria-label="Finding A Bed Tonight — go to home page"
          >
            {isMobile ? <FormattedMessage id="app.nameShort" /> : <FormattedMessage id="app.name" />}
          </a>
        </h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: isMobile ? '8px' : '12px' }}>
          {/* Desktop-only inline controls */}
          {!isMobile && user && (
            <span style={{ fontSize: text.base, color: color.headerText }}>
              {user.displayName || user.tenantName || ''}
            </span>
          )}
          {!isMobile && <LocaleSelector locale={locale} onLocaleChange={onLocaleChange} />}

          {/* Always visible: queue indicator + notification bell */}
          <QueueStatusIndicator count={queueSize} />
          <NotificationBell
            notifications={notifications}
            unreadCount={unreadCount}
            onMarkRead={markRead}
            onMarkAllRead={markAllRead}
            onDismiss={dismiss}
            onLoadMore={loadMore}
            hasMore={hasMore}
            loadingMore={loadingMore}
          />

          {/* Desktop-only inline buttons */}
          {!isMobile && (
            <>
              <button
                onClick={() => setChangePasswordOpen(true)}
                aria-label={intl.formatMessage({ id: 'password.change.title' })}
                data-testid="change-password-button"
                style={{
                  padding: '8px 16px',
                  backgroundColor: 'transparent',
                  color: color.headerText,
                  border: '1px solid rgba(255,255,255,0.3)',
                  borderRadius: '6px',
                  cursor: 'pointer',
                  fontSize: text.base,
                  minHeight: '44px',
                  minWidth: '44px',
                }}
              >
                <FormattedMessage id="password.change.button" />
              </button>
              <button
                onClick={() => navigate('/settings/totp')}
                data-testid="totp-settings-button"
                style={{
                  padding: '8px 16px',
                  backgroundColor: 'transparent',
                  color: color.headerText,
                  border: '1px solid rgba(255,255,255,0.3)',
                  borderRadius: '6px',
                  cursor: 'pointer',
                  fontSize: text.base,
                  minHeight: '44px',
                  minWidth: '44px',
                }}
              >
                <FormattedMessage id="totp.settingsButton" defaultMessage="Security" />
              </button>
              <button
                onClick={handleLogout}
                style={{
                  padding: '8px 16px',
                  backgroundColor: 'transparent',
                  color: color.headerText,
                  border: '1px solid rgba(255,255,255,0.5)',
                  borderRadius: '6px',
                  cursor: 'pointer',
                  fontSize: text.base,
                  minHeight: '44px',
                  minWidth: '44px',
                }}
              >
                <FormattedMessage id="nav.logout" />
              </button>
            </>
          )}

          {/* Mobile kebab overflow menu */}
          {isMobile && (
            <div ref={kebabRef} style={{ position: 'relative' }}>
              <button
                ref={kebabButtonRef}
                data-testid="header-kebab-menu"
                onClick={() => setKebabOpen(!kebabOpen)}
                aria-label="Menu"
                aria-expanded={kebabOpen}
                style={{
                  padding: '8px',
                  backgroundColor: 'transparent',
                  color: color.headerText,
                  border: '1px solid rgba(255,255,255,0.3)',
                  borderRadius: '6px',
                  cursor: 'pointer',
                  fontSize: '20px',
                  lineHeight: 1,
                  minHeight: '44px',
                  minWidth: '44px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                ⋮
              </button>
              {kebabOpen && (
                <div
                  data-testid="header-overflow-dropdown"
                  role="menu"
                  style={{
                    position: 'absolute',
                    top: '100%',
                    right: 0,
                    marginTop: '4px',
                    backgroundColor: color.bg,
                    border: `1px solid ${color.border}`,
                    borderRadius: '8px',
                    boxShadow: '0 4px 12px rgba(0,0,0,0.15)',
                    minWidth: '200px',
                    zIndex: 1000,
                    padding: '4px 0',
                  }}
                >
                  {/* Username display */}
                  {user && (
                    <div
                      data-testid="header-overflow-username"
                      style={{
                        padding: '12px 16px',
                        fontSize: text.sm,
                        color: color.textTertiary,
                        borderBottom: `1px solid ${color.border}`,
                      }}
                    >
                      {user.displayName || user.tenantName || ''}
                    </div>
                  )}
                  {/* Language selector */}
                  <div style={{ padding: '8px 16px', minHeight: '44px', display: 'flex', alignItems: 'center' }}>
                    <LocaleSelector locale={locale} onLocaleChange={onLocaleChange} />
                  </div>
                  {/* Change Password */}
                  <button
                    data-testid="header-overflow-password"
                    role="menuitem"
                    onClick={() => { setKebabOpen(false); setChangePasswordOpen(true); }}
                    style={{
                      display: 'block',
                      width: '100%',
                      padding: '12px 16px',
                      backgroundColor: 'transparent',
                      border: 'none',
                      textAlign: 'left',
                      cursor: 'pointer',
                      fontSize: text.base,
                      color: color.text,
                      minHeight: '44px',
                    }}
                  >
                    <FormattedMessage id="password.change.button" />
                  </button>
                  {/* Security */}
                  <button
                    data-testid="header-overflow-security"
                    role="menuitem"
                    onClick={() => { setKebabOpen(false); navigate('/settings/totp'); }}
                    style={{
                      display: 'block',
                      width: '100%',
                      padding: '12px 16px',
                      backgroundColor: 'transparent',
                      border: 'none',
                      textAlign: 'left',
                      cursor: 'pointer',
                      fontSize: text.base,
                      color: color.text,
                      minHeight: '44px',
                    }}
                  >
                    <FormattedMessage id="totp.settingsButton" defaultMessage="Security" />
                  </button>
                  {/* Sign Out */}
                  <button
                    data-testid="header-overflow-signout"
                    role="menuitem"
                    onClick={() => { setKebabOpen(false); handleLogout(); }}
                    style={{
                      display: 'block',
                      width: '100%',
                      padding: '12px 16px',
                      backgroundColor: 'transparent',
                      border: 'none',
                      borderTop: `1px solid ${color.border}`,
                      textAlign: 'left',
                      cursor: 'pointer',
                      fontSize: text.base,
                      color: color.error,
                      minHeight: '44px',
                    }}
                  >
                    <FormattedMessage id="nav.logout" />
                  </button>
                </div>
              )}
            </div>
          )}
        </div>
      </header>

      <ConnectionStatusBanner connected={connected} />

      <div style={{ display: 'flex', flex: 1 }}>
        {/* Desktop Sidebar */}
        {!isMobile && (
          <nav
            style={{
              width: '220px',
              backgroundColor: color.bgTertiary,
              borderRight: `1px solid ${color.border}`,
              padding: '16px 0',
            }}
          >
            {visibleNavItems.map((item) => (
              <button
                key={item.path}
                onClick={() => navigate(item.path)}
                style={{
                  display: 'block',
                  width: '100%',
                  padding: '14px 20px',
                  border: 'none',
                  backgroundColor: isActive(item.path) ? color.primaryLight : 'transparent',
                  color: isActive(item.path) ? color.primaryText : color.textSecondary,
                  textAlign: 'left',
                  cursor: 'pointer',
                  fontSize: text.base,
                  fontWeight: isActive(item.path) ? weight.semibold : weight.normal,
                  minHeight: '44px',
                }}
              >
                <FormattedMessage id={item.labelId} />
              </button>
            ))}
          </nav>
        )}

        {/* Main Content */}
        <main
          id="main-content"
          ref={mainRef}
          tabIndex={-1}
          style={{
            flex: 1,
            padding: '24px',
            paddingBottom: isMobile ? '80px' : '24px',
            overflowY: 'auto',
            outline: 'none',
          }}
        >
          {children}
          {appVersion && (
            <footer
              data-testid="app-version"
              style={{
                marginTop: '32px',
                paddingTop: '12px',
                borderTop: `1px solid ${color.border}`,
                fontSize: text.xs,
                color: color.textTertiary,
                textAlign: 'center',
              }}
            >
              Finding A Bed Tonight v{appVersion}
            </footer>
          )}
        </main>
      </div>

      {/* Mobile Bottom Nav */}
      {isMobile && (
        <nav
          style={{
            position: 'fixed',
            bottom: 0,
            left: 0,
            right: 0,
            display: 'flex',
            justifyContent: 'space-around',
            backgroundColor: color.bg,
            borderTop: `1px solid ${color.border}`,
            padding: '8px 0',
            zIndex: 900,
          }}
        >
          {visibleNavItems.map((item) => (
            <button
              key={item.path}
              onClick={() => navigate(item.path)}
              style={{
                flex: 1,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: '10px 4px',
                border: 'none',
                backgroundColor: 'transparent',
                color: isActive(item.path) ? color.primaryText : color.textMuted,
                cursor: 'pointer',
                fontSize: text.xs,
                fontWeight: isActive(item.path) ? weight.semibold : weight.normal,
                minHeight: '44px',
                minWidth: '44px',
              }}
            >
              <FormattedMessage id={item.labelId} />
            </button>
          ))}
        </nav>
      )}
    </div>
  );
}
