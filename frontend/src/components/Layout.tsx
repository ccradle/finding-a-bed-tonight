import { type ReactNode, useState, useEffect, useRef } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { useAuth } from '../auth/useAuth';
import { getDefaultRouteForRoles } from '../auth/AuthGuard';
import { LocaleSelector } from './LocaleSelector';
import { OfflineBanner } from './OfflineBanner';
import { SessionTimeoutWarning } from './SessionTimeoutWarning';
import { ChangePasswordModal } from './ChangePasswordModal';
import { text, weight } from '../theme/typography';

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

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const handlePasswordChanged = () => {
    setChangePasswordOpen(false);
    logout();
    navigate('/login');
  };

  const isActive = (path: string) => location.pathname.startsWith(path);

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
    // eslint-disable-next-line react-hooks/set-state-in-effect -- Route change announcement requires setState; this is the recommended a11y pattern
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
            padding: '12px 20px', backgroundColor: '#1a56db', color: '#fff',
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
          padding: '12px 20px',
          backgroundColor: '#1a56db',
          color: '#ffffff',
          minHeight: '56px',
        }}
      >
        <h1 style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold }}>
          <a
            href="#"
            onClick={(e) => { e.preventDefault(); navigate(user ? getDefaultRouteForRoles(user.roles) : '/'); }}
            style={{ color: '#ffffff', textDecoration: 'none', cursor: 'pointer' }}
            aria-label="Finding A Bed Tonight — go to home page"
          >
            <FormattedMessage id="app.name" />
          </a>
        </h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {user && (
            <span style={{ fontSize: text.base, opacity: 0.9 }}>
              {user.displayName || user.tenantName || ''}
            </span>
          )}
          <LocaleSelector locale={locale} onLocaleChange={onLocaleChange} />
          <button
            onClick={() => setChangePasswordOpen(true)}
            aria-label={intl.formatMessage({ id: 'password.change.title' })}
            data-testid="change-password-button"
            style={{
              padding: '8px 16px',
              backgroundColor: 'transparent',
              color: '#ffffff',
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
            onClick={handleLogout}
            style={{
              padding: '8px 16px',
              backgroundColor: 'transparent',
              color: '#ffffff',
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
        </div>
      </header>

      <div style={{ display: 'flex', flex: 1 }}>
        {/* Desktop Sidebar */}
        {!isMobile && (
          <nav
            style={{
              width: '220px',
              backgroundColor: '#f3f4f6',
              borderRight: '1px solid #e5e7eb',
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
                  backgroundColor: isActive(item.path) ? '#dbeafe' : 'transparent',
                  color: isActive(item.path) ? '#1a56db' : '#374151',
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
            backgroundColor: '#ffffff',
            borderTop: '1px solid #e5e7eb',
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
                color: isActive(item.path) ? '#1a56db' : '#6b7280',
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
