import { useState, useEffect, lazy, Suspense } from 'react';
import { FormattedMessage } from 'react-intl';
import { useLocation } from 'react-router-dom';
import { color } from '../../theme/colors';
import { text, weight } from '../../theme/typography';
import { ReservationSettings, Spinner, TabErrorBoundary } from './components';
import type { TabKey } from './types';

// Lazy-load all tabs — each becomes a separate Vite chunk.
// Only the active tab's code is downloaded.
const UsersTab = lazy(() => import('./tabs/UsersTab'));
const SheltersTab = lazy(() => import('./tabs/SheltersTab'));
const ApiKeysTab = lazy(() => import('./tabs/ApiKeysTab'));
const ImportsTab = lazy(() => import('./tabs/ImportsTab'));
const SubscriptionsTab = lazy(() => import('./tabs/SubscriptionsTab'));
const SurgeTab = lazy(() => import('./tabs/SurgeTab'));
const ObservabilityTab = lazy(() => import('./tabs/ObservabilityTab'));
const OAuth2ProvidersTab = lazy(() => import('./tabs/OAuth2ProvidersTab'));
const HmisExportTab = lazy(() => import('./tabs/HmisExportTab'));
const AnalyticsTab = lazy(() => import('./tabs/AnalyticsTab'));
const DvEscalationsTab = lazy(() => import('./tabs/DvEscalationsTab'));

// Future: filter by user role for per-tab permissions (see design D5)
const TABS: { key: TabKey; labelId: string }[] = [
  { key: 'users', labelId: 'admin.users' },
  { key: 'shelters', labelId: 'admin.shelters' },
  { key: 'surge', labelId: 'admin.surge' },
  { key: 'dvEscalations', labelId: 'admin.dvEscalations' },
  { key: 'apiKeys', labelId: 'admin.apiKeys' },
  { key: 'imports', labelId: 'admin.imports' },
  { key: 'subscriptions', labelId: 'admin.subscriptions' },
  { key: 'observability', labelId: 'admin.observability' },
  { key: 'oauth2Providers', labelId: 'admin.oauth2Providers' },
  { key: 'hmisExport', labelId: 'admin.hmisExport' },
  { key: 'analytics', labelId: 'admin.analytics' },
];

const TAB_COMPONENTS: Record<TabKey, React.LazyExoticComponent<React.ComponentType>> = {
  users: UsersTab,
  shelters: SheltersTab,
  apiKeys: ApiKeysTab,
  imports: ImportsTab,
  subscriptions: SubscriptionsTab,
  surge: SurgeTab,
  observability: ObservabilityTab,
  oauth2Providers: OAuth2ProvidersTab,
  hmisExport: HmisExportTab,
  analytics: AnalyticsTab,
  dvEscalations: DvEscalationsTab,
};

/**
 * Map a hash string (with or without leading '#') to a TabKey, or null if
 * the hash doesn't correspond to a registered tab. Used by T-41 (Session 6)
 * so the CriticalNotificationBanner CTA's link to {@code /admin#dvEscalations}
 * actually pre-selects the DV Escalations tab when the user lands.
 */
function tabKeyFromHash(hash: string): TabKey | null {
  const trimmed = hash.replace(/^#/, '');
  if (!trimmed) return null;
  const match = TABS.find((t) => t.key === trimmed);
  return match ? match.key : null;
}

export function AdminPanel() {
  // T-41 (Session 6): react to URL hash changes via react-router's useLocation
  // hook. This is more robust than `window.addEventListener('hashchange', ...)`
  // because react-router's `navigate('/admin#foo')` uses pushState which does
  // NOT reliably fire the native `hashchange` event across browsers (war room
  // round 9 — Riley Cho caught T-43c failing because of this exact gotcha).
  // useLocation() re-runs on every router navigation, including hash-only
  // changes, so the effect below fires correctly.
  const location = useLocation();

  const [activeTab, setActiveTab] = useState<TabKey>(
    () => tabKeyFromHash(typeof window !== 'undefined' ? window.location.hash : '') ?? 'users'
  );
  const ActiveTabComponent = TAB_COMPONENTS[activeTab];

  // React to subsequent hash changes — e.g. user already on /admin clicks the
  // banner CTA, the URL gains '#dvEscalations'. The pathname is unchanged so
  // the AdminPanel component does NOT remount; this effect bridges the gap.
  useEffect(() => {
    const fromHash = tabKeyFromHash(location.hash);
    if (fromHash) setActiveTab(fromHash);
  }, [location.hash]);

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      {/* Header */}
      <div style={{
        background: `linear-gradient(135deg, ${color.headerGradientStart} 0%, ${color.headerGradientMid} 50%, ${color.headerGradientEnd} 100%)`,
        borderRadius: 16, padding: '28px 24px', marginBottom: 20, color: color.textInverse,
        boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
      }}>
        <h1 style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="admin.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: color.headerText }}>
          <FormattedMessage id="admin.subtitle" />
        </p>
      </div>

      {/* Reservation Settings (tenant-wide, always visible) */}
      <ReservationSettings />

      {/* Tab bar — W3C APG Tabs pattern (WCAG 2.1.1) */}
      <div
        role="tablist"
        aria-label="Administration sections"
        style={{
          display: 'flex', overflowX: 'auto', gap: 0,
          borderBottom: `2px solid ${color.border}`, marginBottom: 20,
          WebkitOverflowScrolling: 'touch',
        }}
        onKeyDown={(e) => {
          const tabKeys = TABS.map(t => t.key);
          const currentIndex = tabKeys.indexOf(activeTab);
          let newIndex = currentIndex;
          if (e.key === 'ArrowRight') newIndex = (currentIndex + 1) % tabKeys.length;
          else if (e.key === 'ArrowLeft') newIndex = (currentIndex - 1 + tabKeys.length) % tabKeys.length;
          else if (e.key === 'Home') newIndex = 0;
          else if (e.key === 'End') newIndex = tabKeys.length - 1;
          else return;
          e.preventDefault();
          setActiveTab(tabKeys[newIndex]);
          const btn = e.currentTarget.querySelector(`[data-tab-key="${tabKeys[newIndex]}"]`) as HTMLElement;
          btn?.focus();
        }}
      >
        {TABS.map((tab) => (
          <button
            key={tab.key}
            role="tab"
            aria-selected={activeTab === tab.key}
            aria-controls={`tabpanel-${tab.key}`}
            tabIndex={activeTab === tab.key ? 0 : -1}
            data-tab-key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            style={{
              padding: '12px 18px', minHeight: 44, whiteSpace: 'nowrap',
              border: 'none', backgroundColor: 'transparent', cursor: 'pointer',
              fontSize: text.base, fontWeight: activeTab === tab.key ? weight.bold : weight.medium,
              color: activeTab === tab.key ? color.primaryText : color.textTertiary,
              borderBottom: activeTab === tab.key ? `3px solid ${color.primaryText}` : '3px solid transparent',
              marginBottom: -2, transition: 'color 0.12s, border-color 0.12s',
            }}
          >
            <FormattedMessage id={tab.labelId} />
          </button>
        ))}
      </div>

      {/* Tab content — lazy loaded with ErrorBoundary + Suspense */}
      <div role="tabpanel" id={`tabpanel-${activeTab}`} aria-labelledby={activeTab}>
        <TabErrorBoundary key={activeTab} tabName={activeTab}>
          <Suspense fallback={<Spinner />}>
            <ActiveTabComponent />
          </Suspense>
        </TabErrorBoundary>
      </div>
    </div>
  );
}
