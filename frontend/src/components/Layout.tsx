import { type ReactNode, useState, useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { FormattedMessage } from 'react-intl';
import { useAuth } from '../auth/useAuth';
import { LocaleSelector } from './LocaleSelector';
import { OfflineBanner } from './OfflineBanner';

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
  const { user, logout } = useAuth();
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

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const isActive = (path: string) => location.pathname.startsWith(path);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
      <OfflineBanner />

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
        <h1 style={{ margin: 0, fontSize: '18px', fontWeight: 700 }}>
          <FormattedMessage id="app.name" />
        </h1>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          {user && (
            <span style={{ fontSize: '14px', opacity: 0.9 }}>
              {user.userId}
            </span>
          )}
          <LocaleSelector locale={locale} onLocaleChange={onLocaleChange} />
          <button
            onClick={handleLogout}
            style={{
              padding: '8px 16px',
              backgroundColor: 'transparent',
              color: '#ffffff',
              border: '1px solid rgba(255,255,255,0.5)',
              borderRadius: '6px',
              cursor: 'pointer',
              fontSize: '14px',
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
                  fontSize: '15px',
                  fontWeight: isActive(item.path) ? 600 : 400,
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
          style={{
            flex: 1,
            padding: '24px',
            paddingBottom: isMobile ? '80px' : '24px',
            overflowY: 'auto',
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
                fontSize: '12px',
                fontWeight: isActive(item.path) ? 600 : 400,
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
