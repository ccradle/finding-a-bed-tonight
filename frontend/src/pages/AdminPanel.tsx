import { useState, useEffect, useCallback, useContext, lazy, Suspense } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';
import { AuthContext } from '../auth/AuthContext';

// Lazy-load Analytics tab — ~200KB Recharts bundle only downloads when admin opens it.
const LazyAnalyticsTab = lazy(() => import('./AnalyticsTab'));

// --- Types ---

interface User {
  id: string;
  email: string;
  displayName: string;
  roles: string[];
  dvAccess: boolean;
}

interface ShelterListItem {
  shelter: {
    id: string;
    name: string;
    addressCity: string;
    updatedAt: string;
  };
  availabilitySummary: {
    totalBedsAvailable: number | null;
    dataFreshness: string;
  } | null;
}

interface ApiKeyRow {
  id: string;
  suffix: string;
  label: string;
  role: string;
  active: boolean;
  createdAt: string;
}

interface ApiKeyCreateResponse {
  id: string;
  plaintextKey: string;
  suffix: string;
  label: string;
  role: string;
}

interface ImportRow {
  id: string;
  importType: string;
  filename: string;
  created: number;
  updated: number;
  skipped: number;
  errors: number;
  createdAt: string;
}

interface SubscriptionRow {
  id: string;
  eventType: string;
  callbackUrl: string;
  status: string;
  createdAt: string;
}

type TabKey = 'users' | 'shelters' | 'apiKeys' | 'imports' | 'subscriptions' | 'surge' | 'observability' | 'oauth2Providers' | 'hmisExport' | 'analytics';

const TABS: { key: TabKey; labelId: string }[] = [
  { key: 'users', labelId: 'admin.users' },
  { key: 'shelters', labelId: 'admin.shelters' },
  { key: 'surge', labelId: 'admin.surge' },
  { key: 'apiKeys', labelId: 'admin.apiKeys' },
  { key: 'imports', labelId: 'admin.imports' },
  { key: 'subscriptions', labelId: 'admin.subscriptions' },
  { key: 'observability', labelId: 'admin.observability' },
  { key: 'oauth2Providers', labelId: 'admin.oauth2Providers' },
  { key: 'hmisExport', labelId: 'admin.hmisExport' },
  { key: 'analytics', labelId: 'admin.analytics' },
];

const ROLE_OPTIONS = ['PLATFORM_ADMIN', 'COC_ADMIN', 'COORDINATOR', 'OUTREACH_WORKER'];

// --- Reservation Settings (tenant-wide) ---

function ReservationSettings() {
  const intl = useIntl();
  const { user } = useContext(AuthContext);
  const tenantId = user?.tenantId;
  const [holdDuration, setHoldDuration] = useState(90);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    if (!tenantId) return;
    (async () => {
      try {
        const config = await api.get<Record<string, unknown>>(`/api/v1/tenants/${tenantId}/config`);
        if (config && typeof config === 'object' && 'hold_duration_minutes' in config) {
          setHoldDuration(Number(config.hold_duration_minutes) || 90);
        }
        setLoaded(true);
      } catch { setLoaded(true); }
    })();
  }, [tenantId]);

  const handleSave = async () => {
    if (!tenantId) return;
    setSaving(true); setMessage(null);
    try {
      // GET current config, merge hold_duration_minutes, PUT back
      const current = await api.get<Record<string, unknown>>(`/api/v1/tenants/${tenantId}/config`);
      await api.put(`/api/v1/tenants/${tenantId}/config`, { ...current, hold_duration_minutes: holdDuration });
      setMessage({ type: 'success', text: intl.formatMessage({ id: 'admin.holdDuration.saved' }) });
    } catch {
      setMessage({ type: 'error', text: intl.formatMessage({ id: 'admin.holdDuration.saveError' }) });
    } finally { setSaving(false); }
  };

  if (!loaded) return null;

  return (
    <div style={{ background: '#fff', borderRadius: 12, padding: 16, marginBottom: 16, border: '1px solid #e2e8f0', boxShadow: '0 1px 3px rgba(0,0,0,0.06)' }}
      data-testid="reservation-settings">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <label htmlFor="hold-duration-input" style={{ fontSize: 14, fontWeight: 600, color: '#0f172a' }}>
          <FormattedMessage id="admin.holdDuration.label" defaultMessage="Bed Hold Duration" />
        </label>
        <input
          id="hold-duration-input"
          type="number"
          min={5}
          max={480}
          step={5}
          value={holdDuration}
          onChange={e => setHoldDuration(Math.max(5, Math.min(480, parseInt(e.target.value) || 90)))}
          aria-label="Hold duration in minutes"
          data-testid="hold-duration-input"
          style={{ width: 80, padding: '8px 12px', borderRadius: 8, border: '2px solid #e2e8f0', fontSize: 14, textAlign: 'center', minHeight: 44 }}
        />
        <span style={{ fontSize: 13, color: '#475569' }}>
          <FormattedMessage id="admin.holdDuration.unit" defaultMessage="minutes" />
        </span>
        <button
          onClick={handleSave}
          disabled={saving}
          data-testid="hold-duration-save"
          style={{
            padding: '8px 16px', backgroundColor: '#1a56db', color: '#fff',
            border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 700,
            cursor: saving ? 'default' : 'pointer', minHeight: 44,
          }}
        >
          {saving ? '...' : intl.formatMessage({ id: 'common.save' })}
        </button>
        {message && (
          <span aria-live="polite" style={{ fontSize: 13, color: message.type === 'success' ? '#166534' : '#991b1b', fontWeight: 600 }}>
            {message.text}
          </span>
        )}
      </div>
      <p style={{ fontSize: 12, color: '#475569', margin: '8px 0 0' }}>
        <FormattedMessage id="admin.holdDuration.description" defaultMessage="How long outreach workers can hold a bed before auto-expiry. Hospital deployments may set 120-180 minutes for discharge workflows." />
      </p>
    </div>
  );
}

// --- Main Component ---

export function AdminPanel() {
  const [activeTab, setActiveTab] = useState<TabKey>('users');

  return (
    <div style={{ maxWidth: 900, margin: '0 auto' }}>
      {/* Header */}
      <div style={{
        background: 'linear-gradient(135deg, #0c1929 0%, #1a3a5c 50%, #0f2940 100%)',
        borderRadius: 16, padding: '28px 24px', marginBottom: 20, color: '#fff',
        boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
      }}>
        <h1 style={{ margin: 0, fontSize: 24, fontWeight: 800, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="admin.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: 14, color: '#94b8d8' }}>
          <FormattedMessage id="admin.subtitle" />
        </p>
      </div>

      {/* Reservation Settings (tenant-wide, always visible) */}
      <ReservationSettings />

      {/* Tab bar — W3C APG Tabs pattern (WCAG 2.1.1, D7) */}
      <div
        role="tablist"
        aria-label="Administration sections"
        style={{
          display: 'flex', overflowX: 'auto', gap: 0,
          borderBottom: '2px solid #e2e8f0', marginBottom: 20,
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
          // Focus the new tab button
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
              fontSize: 14, fontWeight: activeTab === tab.key ? 700 : 500,
              color: activeTab === tab.key ? '#1a56db' : '#475569',
              borderBottom: activeTab === tab.key ? '3px solid #1a56db' : '3px solid transparent',
              marginBottom: -2, transition: 'color 0.12s, border-color 0.12s',
            }}
          >
            <FormattedMessage id={tab.labelId} />
          </button>
        ))}
      </div>

      {/* Tab content — role="tabpanel" per W3C APG */}
      <div role="tabpanel" id={`tabpanel-${activeTab}`} aria-labelledby={activeTab}>
        {activeTab === 'users' && <UsersTab />}
        {activeTab === 'shelters' && <SheltersTab />}
        {activeTab === 'apiKeys' && <ApiKeysTab />}
        {activeTab === 'imports' && <ImportsTab />}
        {activeTab === 'subscriptions' && <SubscriptionsTab />}
        {activeTab === 'surge' && <SurgeTab />}
        {activeTab === 'observability' && <ObservabilityTab />}
        {activeTab === 'oauth2Providers' && <OAuth2ProvidersTab />}
        {activeTab === 'hmisExport' && <HmisExportTab />}
        {activeTab === 'analytics' && (
          <Suspense fallback={<div style={{ textAlign: 'center', padding: 40, color: '#475569' }}>Loading analytics...</div>}>
            <LazyAnalyticsTab />
          </Suspense>
        )}
      </div>
    </div>
  );
}

// --- Shared Styles ---

const tableStyle: React.CSSProperties = {
  width: '100%', borderCollapse: 'collapse', fontSize: 14,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left', padding: '10px 14px', fontWeight: 700, color: '#0f172a',
  borderBottom: '2px solid #e2e8f0', fontSize: 12, textTransform: 'uppercase',
  letterSpacing: '0.04em',
};

const tdStyle = (index: number): React.CSSProperties => ({
  padding: '12px 14px', borderBottom: '1px solid #f1f5f9',
  backgroundColor: index % 2 === 0 ? '#fff' : '#f9fafb',
  color: '#0f172a',
});

const primaryBtnStyle: React.CSSProperties = {
  padding: '12px 20px', backgroundColor: '#1a56db', color: '#fff',
  border: 'none', borderRadius: 10, fontSize: 14, fontWeight: 700,
  cursor: 'pointer', minHeight: 44,
};

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '12px 14px', borderRadius: 10,
  border: '2px solid #e2e8f0', fontSize: 14, boxSizing: 'border-box',
  color: '#0f172a', fontWeight: 500, outline: 'none',
};

function StatusBadge({ active, yesId, noId }: { active: boolean; yesId: string; noId: string }) {
  return (
    <span style={{
      padding: '4px 10px', borderRadius: 6, fontSize: 12, fontWeight: 600,
      backgroundColor: active ? '#f0fdf4' : '#fef2f2',
      color: active ? '#166534' : '#991b1b',
      border: `1px solid ${active ? '#bbf7d0' : '#fecaca'}`,
    }}>
      <FormattedMessage id={active ? yesId : noId} />
    </span>
  );
}

function RoleBadge({ role }: { role: string }) {
  return (
    <span style={{
      padding: '3px 8px', borderRadius: 6, fontSize: 11, fontWeight: 600,
      backgroundColor: '#eff6ff', color: '#1e40af', marginRight: 4,
      border: '1px solid #bfdbfe',
    }}>{role}</span>
  );
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div style={{
      backgroundColor: '#fef2f2', color: '#991b1b', padding: '14px 18px',
      borderRadius: 12, marginBottom: 16, fontSize: 14, fontWeight: 500,
    }}>{message}</div>
  );
}

function NoData() {
  return (
    <div style={{ textAlign: 'center', padding: 40, color: '#6b7280', fontSize: 14, fontWeight: 500 }}>
      <FormattedMessage id="admin.noData" />
    </div>
  );
}

function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: '#6b7280' }}>
      <div style={{
        width: 32, height: 32, border: '3px solid #e2e8f0', borderTopColor: '#1a56db',
        borderRadius: '50%', animation: 'fabt-spin 0.7s linear infinite', margin: '0 auto 10px',
      }} />
      <style>{`@keyframes fabt-spin { to { transform: rotate(360deg); } }`}</style>
    </div>
  );
}

// --- Users Tab ---

function UsersTab() {
  const intl = useIntl();
  const [users, setUsers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formEmail, setFormEmail] = useState('');
  const [formDisplayName, setFormDisplayName] = useState('');
  const [formPassword, setFormPassword] = useState('');
  const [formRoles, setFormRoles] = useState<string[]>([]);
  const [formDvAccess, setFormDvAccess] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<User[]>('/api/v1/users');
      setUsers(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchUsers(); }, [fetchUsers]);

  const toggleRole = (role: string) => {
    setFormRoles((prev) =>
      prev.includes(role) ? prev.filter((r) => r !== role) : [...prev, role]
    );
  };

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post('/api/v1/users', {
        email: formEmail,
        displayName: formDisplayName,
        password: formPassword,
        roles: formRoles,
        dvAccess: formDvAccess,
      });
      setShowForm(false);
      setFormEmail('');
      setFormDisplayName('');
      setFormPassword('');
      setFormRoles([]);
      setFormDvAccess(false);
      await fetchUsers();
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <button onClick={() => setShowForm(!showForm)} style={primaryBtnStyle}>
          <FormattedMessage id="admin.createUser" />
        </button>
      </div>

      {/* Create user form */}
      {showForm && (
        <div style={{
          padding: 20, border: '2px solid #e2e8f0', borderRadius: 14,
          marginBottom: 20, backgroundColor: '#f9fafb',
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.email" />
              </label>
              <input value={formEmail} onChange={(e) => setFormEmail(e.target.value)}
                type="email" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.displayName" />
              </label>
              <input value={formDisplayName} onChange={(e) => setFormDisplayName(e.target.value)}
                style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                Password
              </label>
              <input value={formPassword} onChange={(e) => setFormPassword(e.target.value)}
                type="password" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.roles" />
              </label>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {ROLE_OPTIONS.map((role) => (
                  <button
                    key={role}
                    onClick={() => toggleRole(role)}
                    style={{
                      padding: '8px 14px', borderRadius: 8, border: `2px solid ${formRoles.includes(role) ? '#1a56db' : '#e2e8f0'}`,
                      backgroundColor: formRoles.includes(role) ? '#eff6ff' : '#fff',
                      color: formRoles.includes(role) ? '#1a56db' : '#475569',
                      fontSize: 13, fontWeight: 600, cursor: 'pointer', minHeight: 40,
                    }}
                  >{role}</button>
                ))}
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569' }}>
                <FormattedMessage id="admin.dvAccess" />
              </label>
              <button
                onClick={() => setFormDvAccess(!formDvAccess)}
                style={{
                  padding: '6px 14px', borderRadius: 8, border: '2px solid',
                  borderColor: formDvAccess ? '#22c55e' : '#e2e8f0',
                  backgroundColor: formDvAccess ? '#f0fdf4' : '#fff',
                  color: formDvAccess ? '#166534' : '#991b1b',
                  fontSize: 13, fontWeight: 600, cursor: 'pointer',
                }}
              >
                {formDvAccess ? 'ON' : 'OFF'}
              </button>
            </div>
            <button onClick={handleCreate} disabled={submitting}
              style={{ ...primaryBtnStyle, width: '100%', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? '...' : <FormattedMessage id="admin.createUser" />}
            </button>
          </div>
        </div>
      )}

      {/* Users table */}
      {users.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}><FormattedMessage id="admin.email" /></th>
                <th style={thStyle}><FormattedMessage id="admin.displayName" /></th>
                <th style={thStyle}><FormattedMessage id="admin.roles" /></th>
                <th style={thStyle}><FormattedMessage id="admin.dvAccess" /></th>
              </tr>
            </thead>
            <tbody>
              {users.map((u, i) => (
                <tr key={u.id}>
                  <td style={tdStyle(i)}>{u.email}</td>
                  <td style={tdStyle(i)}>{u.displayName}</td>
                  <td style={tdStyle(i)}>
                    {u.roles.map((r) => <RoleBadge key={r} role={r} />)}
                  </td>
                  <td style={tdStyle(i)}>
                    <StatusBadge active={u.dvAccess} yesId="admin.active" noId="admin.inactive" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// --- Shelters Tab ---

function SheltersTab() {
  const intl = useIntl();
  const [shelters, setShelters] = useState<ShelterListItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchShelters = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ShelterListItem[]>('/api/v1/shelters');
      setShelters(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchShelters(); }, [fetchShelters]);

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <a href="/coordinator/shelters/new" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>
          <FormattedMessage id="admin.addShelter" />
        </a>
      </div>

      {shelters.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Name</th>
                <th style={thStyle}>City</th>
                <th style={thStyle}>Beds Available</th>
                <th style={thStyle}>Freshness</th>
                <th style={thStyle}>Updated</th>
              </tr>
            </thead>
            <tbody>
              {shelters.map((item, i) => (
                <tr key={item.shelter.id}>
                  <td style={{ ...tdStyle(i), fontWeight: 600 }}>{item.shelter.name}</td>
                  <td style={tdStyle(i)}>{item.shelter.addressCity}</td>
                  <td style={tdStyle(i)}>
                    {item.availabilitySummary?.totalBedsAvailable != null
                      ? <span style={{ fontWeight: 700, color: item.availabilitySummary.totalBedsAvailable > 0 ? '#166534' : '#991b1b' }}>
                          {item.availabilitySummary.totalBedsAvailable}
                        </span>
                      : <span style={{ color: '#6b7280' }}>—</span>}
                  </td>
                  <td style={tdStyle(i)}>
                    {item.availabilitySummary
                      ? <span style={{
                          padding: '2px 8px', borderRadius: 6, fontSize: 11, fontWeight: 700,
                          backgroundColor: item.availabilitySummary.dataFreshness === 'FRESH' ? '#f0fdf4'
                            : item.availabilitySummary.dataFreshness === 'AGING' ? '#fefce8'
                            : item.availabilitySummary.dataFreshness === 'STALE' ? '#fef2f2' : '#f1f5f9',
                          color: item.availabilitySummary.dataFreshness === 'FRESH' ? '#166534'
                            : item.availabilitySummary.dataFreshness === 'AGING' ? '#854d0e'
                            : item.availabilitySummary.dataFreshness === 'STALE' ? '#991b1b' : '#475569',
                        }}>{item.availabilitySummary.dataFreshness}</span>
                      : <span style={{ color: '#6b7280' }}>—</span>}
                  </td>
                  <td style={tdStyle(i)}>
                    <DataAge dataAgeSeconds={item.shelter.updatedAt ? Math.floor((Date.now() - new Date(item.shelter.updatedAt).getTime()) / 1000) : null} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// --- API Keys Tab ---

function ApiKeysTab() {
  const intl = useIntl();
  const [keys, setKeys] = useState<ApiKeyRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formLabel, setFormLabel] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [newKeyResult, setNewKeyResult] = useState<ApiKeyCreateResponse | null>(null);
  const [copied, setCopied] = useState(false);

  const fetchKeys = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ApiKeyRow[]>('/api/v1/api-keys');
      setKeys(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchKeys(); }, [fetchKeys]);

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const result = await api.post<ApiKeyCreateResponse>('/api/v1/api-keys', { label: formLabel });
      setNewKeyResult(result);
      setShowForm(false);
      setFormLabel('');
      await fetchKeys();
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSubmitting(false);
    }
  };

  const copyKey = async () => {
    if (newKeyResult) {
      await navigator.clipboard.writeText(newKeyResult.plaintextKey);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      {/* New key reveal */}
      {newKeyResult && (
        <div style={{
          padding: 20, backgroundColor: '#fefce8', border: '2px solid #fde047',
          borderRadius: 14, marginBottom: 20,
        }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: '#854d0e', marginBottom: 8 }}>
            <FormattedMessage id="admin.keyWarning" />
          </div>
          <div style={{
            padding: '12px 14px', backgroundColor: '#fff', borderRadius: 8,
            fontFamily: 'monospace', fontSize: 14, color: '#0f172a', wordBreak: 'break-all',
            marginBottom: 10, border: '1px solid #e2e8f0',
          }}>
            {newKeyResult.plaintextKey}
          </div>
          <button onClick={copyKey} style={{
            ...primaryBtnStyle,
            backgroundColor: copied ? '#059669' : '#1a56db',
          }}>
            {copied ? 'Copied!' : 'Copy'}
          </button>
          <button onClick={() => setNewKeyResult(null)} style={{
            marginLeft: 8, padding: '12px 20px', backgroundColor: '#f1f5f9',
            color: '#475569', border: 'none', borderRadius: 10, fontSize: 14,
            fontWeight: 600, cursor: 'pointer', minHeight: 44,
          }}>Dismiss</button>
        </div>
      )}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <button onClick={() => setShowForm(!showForm)} style={primaryBtnStyle}>
          <FormattedMessage id="admin.createKey" />
        </button>
      </div>

      {showForm && (
        <div style={{
          padding: 20, border: '2px solid #e2e8f0', borderRadius: 14,
          marginBottom: 20, backgroundColor: '#f9fafb',
        }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                Label
              </label>
              <input value={formLabel} onChange={(e) => setFormLabel(e.target.value)}
                style={inputStyle} placeholder="e.g. Mobile App" />
            </div>
            <button onClick={handleCreate} disabled={submitting || !formLabel}
              style={{ ...primaryBtnStyle, opacity: submitting || !formLabel ? 0.6 : 1 }}>
              {submitting ? '...' : 'Create'}
            </button>
          </div>
        </div>
      )}

      {keys.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Key</th>
                <th style={thStyle}>Label</th>
                <th style={thStyle}>Role</th>
                <th style={thStyle}>Status</th>
                <th style={thStyle}>Created</th>
              </tr>
            </thead>
            <tbody>
              {keys.map((k, i) => (
                <tr key={k.id}>
                  <td style={{ ...tdStyle(i), fontFamily: 'monospace' }}>****{k.suffix}</td>
                  <td style={tdStyle(i)}>{k.label}</td>
                  <td style={tdStyle(i)}><RoleBadge role={k.role} /></td>
                  <td style={tdStyle(i)}>
                    <StatusBadge active={k.active} yesId="admin.active" noId="admin.inactive" />
                  </td>
                  <td style={tdStyle(i)}>{new Date(k.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// --- Imports Tab ---

function ImportsTab() {
  const intl = useIntl();
  const [imports, setImports] = useState<ImportRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchImports = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<ImportRow[]>('/api/v1/import/history');
      setImports(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchImports(); }, [fetchImports]);

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', gap: 10 }}>
        <a href="/import/hsds" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>HSDS Import</a>
        <a href="/import/211" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>2-1-1 Import</a>
      </div>

      {imports.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Type</th>
                <th style={thStyle}>File</th>
                <th style={thStyle}>Created</th>
                <th style={thStyle}>Updated</th>
                <th style={thStyle}>Skipped</th>
                <th style={thStyle}>Errors</th>
                <th style={thStyle}>Date</th>
              </tr>
            </thead>
            <tbody>
              {imports.map((imp, i) => (
                <tr key={imp.id}>
                  <td style={{ ...tdStyle(i), fontWeight: 600 }}>{imp.importType}</td>
                  <td style={tdStyle(i)}>{imp.filename}</td>
                  <td style={{ ...tdStyle(i), color: '#166534', fontWeight: 600 }}>{imp.created}</td>
                  <td style={{ ...tdStyle(i), color: '#1a56db', fontWeight: 600 }}>{imp.updated}</td>
                  <td style={{ ...tdStyle(i), color: '#854d0e' }}>{imp.skipped}</td>
                  <td style={{
                    ...tdStyle(i),
                    color: imp.errors > 0 ? '#991b1b' : '#475569',
                    fontWeight: imp.errors > 0 ? 700 : 400,
                  }}>{imp.errors}</td>
                  <td style={tdStyle(i)}>{new Date(imp.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// --- Subscriptions Tab ---

function SubscriptionsTab() {
  const intl = useIntl();
  const [subs, setSubs] = useState<SubscriptionRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [formEventType, setFormEventType] = useState('');
  const [formCallbackUrl, setFormCallbackUrl] = useState('');
  const [formCallbackSecret, setFormCallbackSecret] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const fetchSubs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<SubscriptionRow[]>('/api/v1/subscriptions');
      setSubs(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchSubs(); }, [fetchSubs]);

  const handleCreate = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post('/api/v1/subscriptions', {
        eventType: formEventType,
        callbackUrl: formCallbackUrl,
        callbackSecret: formCallbackSecret,
      });
      setShowForm(false);
      setFormEventType('');
      setFormCallbackUrl('');
      setFormCallbackSecret('');
      await fetchSubs();
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <button onClick={() => setShowForm(!showForm)} style={primaryBtnStyle}>
          <FormattedMessage id="admin.newSubscription" />
        </button>
      </div>

      {showForm && (
        <div style={{
          padding: 20, border: '2px solid #e2e8f0', borderRadius: 14,
          marginBottom: 20, backgroundColor: '#f9fafb',
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                Event Type
              </label>
              <input value={formEventType} onChange={(e) => setFormEventType(e.target.value)}
                style={inputStyle} placeholder="e.g. shelter.updated" />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                Callback URL
              </label>
              <input value={formCallbackUrl} onChange={(e) => setFormCallbackUrl(e.target.value)}
                type="url" style={inputStyle} placeholder="https://..." />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', marginBottom: 4, display: 'block' }}>
                Callback Secret
              </label>
              <input value={formCallbackSecret} onChange={(e) => setFormCallbackSecret(e.target.value)}
                type="password" style={inputStyle} />
            </div>
            <button onClick={handleCreate} disabled={submitting || !formEventType || !formCallbackUrl}
              style={{ ...primaryBtnStyle, width: '100%', opacity: submitting || !formEventType || !formCallbackUrl ? 0.6 : 1 }}>
              {submitting ? '...' : <FormattedMessage id="admin.newSubscription" />}
            </button>
          </div>
        </div>
      )}

      {subs.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Event Type</th>
                <th style={thStyle}>Callback URL</th>
                <th style={thStyle}>Status</th>
                <th style={thStyle}>Created</th>
              </tr>
            </thead>
            <tbody>
              {subs.map((s, i) => (
                <tr key={s.id}>
                  <td style={{ ...tdStyle(i), fontWeight: 600 }}>{s.eventType}</td>
                  <td style={{ ...tdStyle(i), fontFamily: 'monospace', fontSize: 13 }}>{s.callbackUrl}</td>
                  <td style={tdStyle(i)}>
                    <StatusBadge active={s.status === 'ACTIVE'} yesId="admin.active" noId="admin.inactive" />
                  </td>
                  <td style={tdStyle(i)}>{new Date(s.createdAt).toLocaleDateString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// --- Surge Tab ---

function SurgeTab() {
  const intl = useIntl();
  const [surges, setSurges] = useState<{ id: string; status: string; reason: string; activatedAt: string; deactivatedAt: string | null }[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [reason, setReason] = useState('');
  const [scheduledEnd, setScheduledEnd] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const fetchSurges = useCallback(async () => {
    setLoading(true);
    try {
      const data = await api.get<typeof surges>('/api/v1/surge-events');
      setSurges(data);
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchSurges(); }, [fetchSurges]);

  const activateSurge = async () => {
    setSubmitting(true);
    setError(null);
    try {
      const body: Record<string, unknown> = { reason };
      if (scheduledEnd) body.scheduledEnd = new Date(scheduledEnd).toISOString();
      await api.post('/api/v1/surge-events', body);
      setShowForm(false);
      setReason('');
      setScheduledEnd('');
      await fetchSurges();
    } catch {
      setError(intl.formatMessage({ id: 'surge.alreadyActive' }));
    } finally {
      setSubmitting(false);
    }
  };

  const deactivateSurge = async (id: string) => {
    try {
      await api.patch(`/api/v1/surge-events/${id}/deactivate`);
      await fetchSurges();
    } catch {
      setError(intl.formatMessage({ id: 'coord.error' }));
    }
  };

  const activeSurge = surges.find(s => s.status === 'ACTIVE');

  if (loading) return <Spinner />;

  return (
    <div>
      {error && <ErrorBox message={error} />}

      {activeSurge && (
        <div style={{
          padding: 20, borderRadius: 14, marginBottom: 20,
          background: 'linear-gradient(135deg, #dc2626 0%, #b91c1c 100%)', color: '#fff',
        }}>
          <div style={{ fontSize: 14, fontWeight: 800, letterSpacing: '0.06em', marginBottom: 6 }}>
            <FormattedMessage id="surge.banner" />
          </div>
          <div style={{ fontSize: 16, fontWeight: 500, marginBottom: 8 }}>{activeSurge.reason}</div>
          <div style={{ fontSize: 12, opacity: 0.85, marginBottom: 12 }}>
            <FormattedMessage id="surge.since" />: {new Date(activeSurge.activatedAt).toLocaleString()}
          </div>
          <button onClick={() => deactivateSurge(activeSurge.id)} style={{
            padding: '10px 20px', borderRadius: 8, border: '2px solid rgba(255,255,255,0.5)',
            backgroundColor: 'transparent', color: '#fff', fontSize: 14, fontWeight: 700, cursor: 'pointer',
          }}><FormattedMessage id="surge.deactivate" /></button>
        </div>
      )}

      {!activeSurge && (
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <button onClick={() => setShowForm(!showForm)} style={{ ...primaryBtnStyle, backgroundColor: '#dc2626' }}>
            <FormattedMessage id="surge.activate" />
          </button>
        </div>
      )}

      {showForm && (
        <div style={{ padding: 20, border: '2px solid #fecaca', borderRadius: 14, marginBottom: 20, backgroundColor: '#fef2f2' }}>
          <div style={{ marginBottom: 12 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="surge.reason" />
            </label>
            <input value={reason} onChange={e => setReason(e.target.value)}
              style={inputStyle} placeholder={intl.formatMessage({ id: 'surge.reasonPlaceholder' })} />
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: '#475569', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="surge.scheduledEnd" />
            </label>
            <input type="datetime-local" value={scheduledEnd} onChange={e => setScheduledEnd(e.target.value)} style={inputStyle} />
          </div>
          <button onClick={activateSurge} disabled={submitting || !reason}
            style={{ ...primaryBtnStyle, backgroundColor: '#dc2626', width: '100%', opacity: submitting || !reason ? 0.6 : 1 }}>
            {submitting ? '...' : intl.formatMessage({ id: 'surge.activate' })}
          </button>
        </div>
      )}

      {surges.length === 0 ? <NoData /> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Status</th>
                <th style={thStyle}><FormattedMessage id="surge.reason" /></th>
                <th style={thStyle}><FormattedMessage id="surge.since" /></th>
                <th style={thStyle}>Ended</th>
              </tr>
            </thead>
            <tbody>
              {surges.map((s, i) => (
                <tr key={s.id}>
                  <td style={tdStyle(i)}>
                    <StatusBadge active={s.status === 'ACTIVE'} yesId="admin.active" noId="admin.inactive" />
                  </td>
                  <td style={{ ...tdStyle(i), fontWeight: 600 }}>{s.reason}</td>
                  <td style={tdStyle(i)}>{new Date(s.activatedAt).toLocaleString()}</td>
                  <td style={tdStyle(i)}>{s.deactivatedAt ? new Date(s.deactivatedAt).toLocaleString() : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

// --- Observability Tab ---

interface ObservabilityConfig {
  prometheusEnabled: boolean;
  tracingEnabled: boolean;
  tracingEndpoint: string;
  monitorStaleIntervalMinutes: number;
  monitorDvCanaryIntervalMinutes: number;
  monitorTemperatureIntervalMinutes: number;
  temperatureThresholdF: number;
}

interface TemperatureStatus {
  temperatureF: number | null;
  stationId: string | null;
  thresholdF: number;
  surgeActive: boolean;
  gapDetected: boolean;
  lastChecked: string | null;
}

function ObservabilityTab() {
  const intl = useIntl();
  const { user } = useContext(AuthContext);
  const tenantId = user?.tenantId;

  const [config, setConfig] = useState<ObservabilityConfig>({
    prometheusEnabled: true, tracingEnabled: false,
    tracingEndpoint: 'http://localhost:4318/v1/traces',
    monitorStaleIntervalMinutes: 5, monitorDvCanaryIntervalMinutes: 15,
    monitorTemperatureIntervalMinutes: 60, temperatureThresholdF: 32,
  });
  const [tempStatus, setTempStatus] = useState<TemperatureStatus | null>(null);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const loadConfig = useCallback(async () => {
    if (!tenantId) return;
    try {
      const data = await api.get<ObservabilityConfig>(`/api/v1/tenants/${tenantId}/observability`);
      setConfig(data);
    } catch { /* use defaults */ }
  }, [tenantId]);

  const loadTempStatus = useCallback(async () => {
    try {
      const data = await api.get<TemperatureStatus>('/api/v1/monitoring/temperature');
      setTempStatus(data);
    } catch { /* monitor may not have run yet */ }
  }, []);

  useEffect(() => { loadConfig(); loadTempStatus(); }, [loadConfig, loadTempStatus]);

  const handleSave = async () => {
    if (!tenantId) return;
    setSaving(true); setMessage(null);
    try {
      await api.put(`/api/v1/tenants/${tenantId}/observability`, {
        prometheus_enabled: config.prometheusEnabled, tracing_enabled: config.tracingEnabled,
        tracing_endpoint: config.tracingEndpoint,
        monitor_stale_interval_minutes: config.monitorStaleIntervalMinutes,
        monitor_dv_canary_interval_minutes: config.monitorDvCanaryIntervalMinutes,
        monitor_temperature_interval_minutes: config.monitorTemperatureIntervalMinutes,
        temperature_threshold_f: config.temperatureThresholdF,
      });
      setMessage({ type: 'success', text: intl.formatMessage({ id: 'admin.observability.saved' }) });
      loadConfig();
    } catch {
      setMessage({ type: 'error', text: intl.formatMessage({ id: 'admin.observability.saveError' }) });
    } finally { setSaving(false); }
  };

  const toggleBtn = (active: boolean): React.CSSProperties => ({
    width: 48, height: 26, borderRadius: 13, border: 'none', cursor: 'pointer',
    background: active ? '#1a56db' : '#cbd5e1', position: 'relative' as const, transition: 'background 0.2s',
  });
  const toggleDot = (active: boolean): React.CSSProperties => ({
    position: 'absolute' as const, top: 3, left: active ? 24 : 3,
    width: 20, height: 20, borderRadius: 10, background: '#fff',
    transition: 'left 0.2s', boxShadow: '0 1px 2px rgba(0,0,0,0.2)',
  });
  const inputStyle: React.CSSProperties = {
    padding: '8px 12px', border: '1px solid #cbd5e1', borderRadius: 8, fontSize: 14, width: 120,
  };
  const sectionStyle: React.CSSProperties = {
    background: '#fff', borderRadius: 12, padding: 20,
    boxShadow: '0 1px 3px rgba(0,0,0,0.08)', marginBottom: 16,
  };
  const rowStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '12px 0', borderBottom: '1px solid #e2e8f0',
  };

  return (
    <div>
      {/* Temperature Status Banner */}
      {tempStatus && tempStatus.temperatureF !== null && (
        <div style={{
          ...sectionStyle,
          background: tempStatus.gapDetected
            ? 'linear-gradient(135deg, #fef3c7 0%, #fde68a 100%)'
            : 'linear-gradient(135deg, #d1fae5 0%, #a7f3d0 100%)',
          border: tempStatus.gapDetected ? '2px solid #f59e0b' : '2px solid #10b981',
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: 28, fontWeight: 800 }}>
              {tempStatus.temperatureF.toFixed(1)}°F
            </span>
            <div>
              <div style={{ fontWeight: 600, fontSize: 14 }}>
                <FormattedMessage id="admin.observability.station" /> {tempStatus.stationId || 'Unknown'}
              </div>
              <div style={{ fontSize: 13, color: '#475569' }}>
                <FormattedMessage id="admin.observability.threshold" />: {tempStatus.thresholdF}°F
                {tempStatus.surgeActive && <span> · <FormattedMessage id="admin.observability.surgeActive" /></span>}
              </div>
            </div>
          </div>
          {tempStatus.gapDetected && (
            <div style={{
              marginTop: 10, padding: '8px 12px', background: 'rgba(245,158,11,0.15)',
              borderRadius: 8, fontSize: 13, fontWeight: 600, color: '#92400e',
            }}>
              {tempStatus.temperatureF.toFixed(0)}°F — <FormattedMessage id="admin.observability.belowThreshold"
                values={{ threshold: tempStatus.thresholdF.toString() }} />.{' '}
              <FormattedMessage id="admin.observability.considerSurge" />
            </div>
          )}
          {tempStatus.lastChecked && (
            <div style={{ fontSize: 12, color: '#475569', marginTop: 6 }}>
              <FormattedMessage id="admin.observability.lastChecked" />: {new Date(tempStatus.lastChecked).toLocaleString()}
            </div>
          )}
        </div>
      )}

      {/* Configuration */}
      <div style={sectionStyle}>
        <h3 style={{ margin: '0 0 16px', fontSize: 16, fontWeight: 700 }}>
          <FormattedMessage id="admin.observability.config" />
        </h3>

        <div style={rowStyle}>
          <span><FormattedMessage id="admin.observability.prometheus" /></span>
          <button onClick={() => setConfig(c => ({ ...c, prometheusEnabled: !c.prometheusEnabled }))}
            style={toggleBtn(config.prometheusEnabled)}
            role="switch" aria-checked={config.prometheusEnabled}
            aria-label="Toggle Prometheus metrics"
            data-testid="toggle-prometheus">
            <span style={toggleDot(config.prometheusEnabled)} />
          </button>
        </div>

        <div style={rowStyle}>
          <span><FormattedMessage id="admin.observability.tracing" /></span>
          <button onClick={() => setConfig(c => ({ ...c, tracingEnabled: !c.tracingEnabled }))}
            style={toggleBtn(config.tracingEnabled)}
            role="switch" aria-checked={config.tracingEnabled}
            aria-label="Toggle OpenTelemetry tracing"
            data-testid="toggle-tracing">
            <span style={toggleDot(config.tracingEnabled)} />
          </button>
        </div>

        {config.tracingEnabled && (
          <div style={{ padding: '12px 0', borderBottom: '1px solid #e2e8f0' }}>
            <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
              <FormattedMessage id="admin.observability.tracingEndpoint" />
            </label>
            <input type="text" value={config.tracingEndpoint}
              onChange={e => setConfig(c => ({ ...c, tracingEndpoint: e.target.value }))}
              style={{ ...inputStyle, width: '100%' }} />
          </div>
        )}

        <div style={{ padding: '16px 0 8px' }}>
          <h4 style={{ margin: '0 0 12px', fontSize: 14, fontWeight: 600, color: '#475569' }}>
            <FormattedMessage id="admin.observability.intervals" />
          </h4>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.staleInterval" />
              </label>
              <input id="stale-interval" type="number" min={1} value={config.monitorStaleIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorStaleIntervalMinutes: parseInt(e.target.value) || 5 }))}
                aria-label="Stale shelter check interval in minutes"
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#6b7280', marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.dvCanaryInterval" />
              </label>
              <input id="dv-canary-interval" type="number" min={1} value={config.monitorDvCanaryIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorDvCanaryIntervalMinutes: parseInt(e.target.value) || 15 }))}
                aria-label="DV canary check interval in minutes"
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#6b7280', marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.tempInterval" />
              </label>
              <input id="temp-interval" type="number" min={1} value={config.monitorTemperatureIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorTemperatureIntervalMinutes: parseInt(e.target.value) || 60 }))}
                aria-label="Temperature check interval in minutes"
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#6b7280', marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.tempThreshold" />
              </label>
              <input id="temp-threshold" data-testid="temp-threshold" type="number" value={config.temperatureThresholdF}
                onChange={e => setConfig(c => ({ ...c, temperatureThresholdF: parseFloat(e.target.value) || 32 }))}
                aria-label="Temperature threshold in Fahrenheit"
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#6b7280', marginLeft: 4 }}>°F</span>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 16 }}>
          <button onClick={handleSave} disabled={saving}
            data-testid="observability-save"
            style={{
              padding: '10px 24px', background: '#1a56db', color: '#fff', border: 'none',
              borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: 'pointer',
              opacity: saving ? 0.6 : 1, minHeight: 44,
            }}>
            {saving ? '...' : intl.formatMessage({ id: 'admin.observability.save' })}
          </button>
          {message && (
            <span style={{ fontSize: 13, fontWeight: 600,
              color: message.type === 'success' ? '#059669' : '#dc2626' }}>
              {message.text}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

// --- OAuth2 Providers Tab ---

interface OAuth2ProviderRow {
  id: string;
  providerName: string;
  enabled: boolean;
  issuerUri: string;
  createdAt: string;
}

const PROVIDER_PRESETS: Record<string, { label: string; issuerUri: string }> = {
  google: { label: 'Google', issuerUri: 'https://accounts.google.com' },
  microsoft: { label: 'Microsoft', issuerUri: '' }, // needs tenant ID
  keycloak: { label: 'Keycloak', issuerUri: 'http://localhost:8180/realms/fabt-dev' },
  custom: { label: '', issuerUri: '' },
};

function OAuth2ProvidersTab() {
  const intl = useIntl();
  const { user } = useContext(AuthContext);
  const tenantId = user?.tenantId;

  const [providers, setProviders] = useState<OAuth2ProviderRow[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [editId, setEditId] = useState<string | null>(null);
  const [providerType, setProviderType] = useState('google');
  const [formName, setFormName] = useState('google');
  const [formClientId, setFormClientId] = useState('');
  const [formClientSecret, setFormClientSecret] = useState('');
  const [formIssuerUri, setFormIssuerUri] = useState(PROVIDER_PRESETS.google.issuerUri);
  const [submitting, setSubmitting] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [testResult, setTestResult] = useState<{ ok: boolean; text: string } | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  const loadProviders = useCallback(async () => {
    if (!tenantId) return;
    try {
      const data = await api.get<OAuth2ProviderRow[]>(`/api/v1/tenants/${tenantId}/oauth2-providers`);
      setProviders(data);
    } catch { /* ignore */ }
  }, [tenantId]);

  useEffect(() => { loadProviders(); }, [loadProviders]);

  const resetForm = () => {
    setShowForm(false);
    setEditId(null);
    setProviderType('google');
    setFormName('');
    setFormClientId('');
    setFormClientSecret('');
    setFormIssuerUri('');
    setTestResult(null);
    setMessage(null);
  };

  const handleProviderTypeChange = (type: string) => {
    setProviderType(type);
    const preset = PROVIDER_PRESETS[type];
    if (preset) {
      setFormIssuerUri(preset.issuerUri);
      if (type !== 'custom') setFormName(type);
    }
  };

  const handleTestConnection = async () => {
    if (!formIssuerUri) return;
    setTestResult(null);
    try {
      const wellKnown = formIssuerUri.replace(/\/$/, '') + '/.well-known/openid-configuration';
      const response = await fetch(wellKnown);
      if (response.ok) {
        setTestResult({ ok: true, text: intl.formatMessage({ id: 'admin.oauth2.testSuccess' }) });
      } else {
        setTestResult({ ok: false, text: intl.formatMessage({ id: 'admin.oauth2.testFailed' }) });
      }
    } catch {
      setTestResult({ ok: false, text: intl.formatMessage({ id: 'admin.oauth2.testFailed' }) });
    }
  };

  const handleSave = async () => {
    if (!tenantId || !formName || !formClientId || (!editId && !formClientSecret)) return;
    setSubmitting(true);
    setMessage(null);
    try {
      if (editId) {
        await api.put(`/api/v1/tenants/${tenantId}/oauth2-providers/${editId}`, {
          clientId: formClientId,
          clientSecret: formClientSecret || undefined,
          issuerUri: formIssuerUri,
        });
      } else {
        await api.post(`/api/v1/tenants/${tenantId}/oauth2-providers`, {
          providerName: formName,
          clientId: formClientId,
          clientSecret: formClientSecret,
          issuerUri: formIssuerUri,
        });
      }
      setMessage({ type: 'success', text: intl.formatMessage({ id: 'admin.oauth2.saved' }) });
      resetForm();
      loadProviders();
    } catch {
      setMessage({ type: 'error', text: intl.formatMessage({ id: 'admin.oauth2.saveError' }) });
    } finally { setSubmitting(false); }
  };

  const handleDelete = async (id: string) => {
    try {
      await api.delete(`/api/v1/tenants/${tenantId}/oauth2-providers/${id}`);
      setMessage({ type: 'success', text: intl.formatMessage({ id: 'admin.oauth2.deleted' }) });
      setDeleteConfirm(null);
      loadProviders();
    } catch {
      setMessage({ type: 'error', text: intl.formatMessage({ id: 'admin.oauth2.saveError' }) });
    }
  };

  const handleToggleEnabled = async (provider: OAuth2ProviderRow) => {
    try {
      await api.put(`/api/v1/tenants/${tenantId}/oauth2-providers/${provider.id}`, {
        enabled: !provider.enabled,
      });
      loadProviders();
    } catch { /* ignore */ }
  };

  const startEdit = (provider: OAuth2ProviderRow) => {
    setEditId(provider.id);
    setFormName(provider.providerName);
    setFormClientId('');
    setFormClientSecret('');
    setFormIssuerUri(provider.issuerUri || '');
    setShowForm(true);
    setTestResult(null);
  };

  const sectionStyle: React.CSSProperties = {
    background: '#fff', borderRadius: 12, padding: 20,
    boxShadow: '0 1px 3px rgba(0,0,0,0.08)', marginBottom: 16,
  };
  const inputStyle: React.CSSProperties = {
    width: '100%', padding: '10px 12px', border: '1px solid #cbd5e1', borderRadius: 8,
    fontSize: 14, boxSizing: 'border-box' as const, marginBottom: 12,
  };

  return (
    <div>
      <div style={sectionStyle}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>
            <FormattedMessage id="admin.oauth2.title" />
          </h3>
          {!showForm && (
            <button onClick={() => { resetForm(); setShowForm(true); }}
              style={{ padding: '8px 16px', background: '#1a56db', color: '#fff', border: 'none',
                borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>
              <FormattedMessage id="admin.oauth2.addProvider" />
            </button>
          )}
        </div>

        {message && (
          <div style={{
            padding: '10px 14px', borderRadius: 8, marginBottom: 12, fontSize: 13, fontWeight: 600,
            background: message.type === 'success' ? '#d1fae5' : '#fef2f2',
            color: message.type === 'success' ? '#065f46' : '#991b1b',
          }}>{message.text}</div>
        )}

        {/* Add/Edit Form */}
        {showForm && (
          <div style={{ background: '#f8fafc', borderRadius: 8, padding: 16, marginBottom: 16, border: '1px solid #e2e8f0' }}>
            {!editId && (
              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
                  <FormattedMessage id="admin.oauth2.providerType" />
                </label>
                <select id="oauth2-provider-type" value={providerType} onChange={e => handleProviderTypeChange(e.target.value)}
                  aria-label="OAuth2 provider type"
                  style={{ ...inputStyle, marginBottom: 12 }}>
                  <option value="google">Google</option>
                  <option value="microsoft">Microsoft</option>
                  <option value="keycloak">Keycloak</option>
                  <option value="custom">{intl.formatMessage({ id: 'admin.oauth2.custom' })}</option>
                </select>
              </div>
            )}

            {(providerType === 'custom' || providerType === 'microsoft') && !editId && (
              <div>
                <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>Provider Name</label>
                <input type="text" value={formName} onChange={e => setFormName(e.target.value)}
                  placeholder={providerType === 'microsoft' ? 'microsoft' : 'my-idp'}
                  style={inputStyle} />
              </div>
            )}

            <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
              <FormattedMessage id="admin.oauth2.clientId" />
            </label>
            <input type="text" value={formClientId} onChange={e => setFormClientId(e.target.value)}
              placeholder="your-client-id" style={inputStyle} />

            <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
              <FormattedMessage id="admin.oauth2.clientSecret" />
            </label>
            <input type="password" value={formClientSecret} onChange={e => setFormClientSecret(e.target.value)}
              placeholder={editId ? intl.formatMessage({ id: 'admin.oauth2.updateSecret' }) : ''}
              style={inputStyle} />
            <div style={{ fontSize: 12, color: '#6b7280', marginTop: -8, marginBottom: 12 }}>
              <FormattedMessage id="admin.oauth2.secretNote" />
            </div>

            <label style={{ display: 'block', fontSize: 13, color: '#475569', marginBottom: 4 }}>
              <FormattedMessage id="admin.oauth2.issuerUri" />
            </label>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <input type="text" value={formIssuerUri} onChange={e => setFormIssuerUri(e.target.value)}
                placeholder="https://accounts.google.com" style={{ ...inputStyle, flex: 1, marginBottom: 0 }} />
              <button onClick={handleTestConnection}
                style={{ padding: '8px 14px', background: '#f1f5f9', border: '1px solid #cbd5e1',
                  borderRadius: 8, fontSize: 13, cursor: 'pointer', whiteSpace: 'nowrap' as const }}>
                <FormattedMessage id="admin.oauth2.testConnection" />
              </button>
            </div>
            {testResult && (
              <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 12,
                color: testResult.ok ? '#059669' : '#dc2626' }}>
                {testResult.text}
              </div>
            )}

            <div style={{ display: 'flex', gap: 8 }}>
              <button onClick={handleSave} disabled={submitting}
                style={{ padding: '10px 20px', background: '#1a56db', color: '#fff', border: 'none',
                  borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: 'pointer',
                  opacity: submitting ? 0.6 : 1 }}>
                {submitting ? '...' : editId ? 'Update' : 'Save'}
              </button>
              <button onClick={resetForm}
                style={{ padding: '10px 20px', background: '#f1f5f9', color: '#475569', border: '1px solid #cbd5e1',
                  borderRadius: 8, fontSize: 14, cursor: 'pointer' }}>
                <FormattedMessage id="admin.cancel" />
              </button>
            </div>
          </div>
        )}

        {/* Provider List */}
        {providers.length === 0 && !showForm ? (
          <div style={{ textAlign: 'center', padding: 32, color: '#6b7280', fontSize: 14 }}>
            <FormattedMessage id="admin.noData" />
          </div>
        ) : (
          <table style={tableStyle}>
            <thead>
              <tr>
                <th style={thStyle}>Provider</th>
                <th style={thStyle}>Status</th>
                <th style={thStyle}>Issuer URI</th>
                <th style={thStyle}>Created</th>
                <th style={thStyle}>Actions</th>
              </tr>
            </thead>
            <tbody>
              {providers.map((p, i) => (
                <tr key={p.id}>
                  <td style={{ ...tdStyle(i), fontWeight: 600 }}>{p.providerName}</td>
                  <td style={tdStyle(i)}>
                    <button onClick={() => handleToggleEnabled(p)}
                      style={{ padding: '2px 10px', borderRadius: 12, border: 'none', fontSize: 12, fontWeight: 600,
                        cursor: 'pointer',
                        background: p.enabled ? '#d1fae5' : '#f1f5f9',
                        color: p.enabled ? '#065f46' : '#475569' }}>
                      {p.enabled ? 'Active' : 'Inactive'}
                    </button>
                  </td>
                  <td style={{ ...tdStyle(i), fontSize: 12, color: '#475569', maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.issuerUri}</td>
                  <td style={tdStyle(i)}>{new Date(p.createdAt).toLocaleDateString()}</td>
                  <td style={tdStyle(i)}>
                    <button onClick={() => startEdit(p)}
                      style={{ marginRight: 8, padding: '4px 10px', fontSize: 12, border: '1px solid #cbd5e1',
                        borderRadius: 6, background: '#fff', cursor: 'pointer' }}>Edit</button>
                    <button onClick={() => setDeleteConfirm(p.id)}
                      style={{ padding: '4px 10px', fontSize: 12, border: '1px solid #fca5a5',
                        borderRadius: 6, background: '#fff', color: '#dc2626', cursor: 'pointer' }}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {/* Delete Confirmation */}
        {deleteConfirm && (
          <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
            background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000 }}>
            <div style={{ background: '#fff', borderRadius: 12, padding: 24, maxWidth: 400, boxShadow: '0 8px 32px rgba(0,0,0,0.2)' }}>
              <p style={{ fontSize: 14, marginBottom: 16 }}>
                <FormattedMessage id="admin.oauth2.deleteConfirm"
                  values={{ name: providers.find(p => p.id === deleteConfirm)?.providerName || '' }} />
              </p>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                <button onClick={() => setDeleteConfirm(null)}
                  style={{ padding: '8px 16px', background: '#f1f5f9', border: '1px solid #cbd5e1',
                    borderRadius: 8, cursor: 'pointer' }}>
                  <FormattedMessage id="admin.cancel" />
                </button>
                <button onClick={() => handleDelete(deleteConfirm)}
                  style={{ padding: '8px 16px', background: '#dc2626', color: '#fff', border: 'none',
                    borderRadius: 8, cursor: 'pointer', fontWeight: 600 }}>Delete</button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

// --- HMIS Export Tab ---

interface HmisInventoryRecord {
  projectId: string | null;
  projectName: string;
  householdType: string;
  bedInventory: number;
  bedsOccupied: number;
  utilizationPercent: number;
  isDvAggregated: boolean;
}

interface HmisAuditEntry {
  vendorType: string;
  pushTimestamp: string;
  status: string;
  recordCount: number;
  errorMessage: string | null;
}

function HmisExportTab() {
  const intl = useIntl();
  const [preview, setPreview] = useState<HmisInventoryRecord[]>([]);
  const [history, setHistory] = useState<HmisAuditEntry[]>([]);
  const [status, setStatus] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [pushing, setPushing] = useState(false);
  const [dvFilter, setDvFilter] = useState<boolean | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [statusData, previewData, historyData] = await Promise.all([
        api.get<any>('/api/v1/hmis/status'),
        api.get<HmisInventoryRecord[]>('/api/v1/hmis/preview'),
        api.get<HmisAuditEntry[]>('/api/v1/hmis/history?limit=20'),
      ]);
      setStatus(statusData);
      setPreview(previewData);
      setHistory(historyData);
    } catch { /* silent */ }
    setLoading(false);
  }, []);

  useEffect(() => { fetchData(); }, [fetchData]);

  const handlePush = async () => {
    setPushing(true);
    try {
      await api.post('/api/v1/hmis/push', {});
      await fetchData();
    } catch { /* silent */ }
    setPushing(false);
  };

  const filteredPreview = dvFilter === null ? preview
    : preview.filter(r => r.isDvAggregated === dvFilter);

  if (loading) return <div style={{ padding: 20, color: '#475569' }}><FormattedMessage id="coord.loading" /></div>;

  return (
    <div>
      {/* Export Status */}
      <div data-testid="hmis-status" style={{ marginBottom: 20 }}>
        <h3 style={{ fontSize: 15, fontWeight: 700, color: '#0f172a', marginBottom: 10 }}>
          <FormattedMessage id="hmis.exportStatus" />
        </h3>
        {status?.vendors?.length > 0 ? (
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            {status.vendors.map((v: any, i: number) => (
              <div key={i} style={{
                padding: '10px 16px', borderRadius: 10, border: '1px solid #e2e8f0',
                backgroundColor: v.enabled ? '#f0fdf4' : '#fef2f2',
              }}>
                <span style={{ fontWeight: 700, fontSize: 13 }}>{v.type}</span>
                <span style={{ marginLeft: 8, fontSize: 12, color: v.enabled ? '#166534' : '#991b1b' }}>
                  {v.enabled ? 'Enabled' : 'Disabled'}
                </span>
                <span style={{ marginLeft: 8, fontSize: 11, color: '#475569' }}>
                  every {v.pushIntervalHours}h
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p style={{ fontSize: 14, color: '#475569' }}><FormattedMessage id="hmis.noVendors" /></p>
        )}
        {status?.deadLetterCount > 0 && (
          <div style={{ marginTop: 8, padding: '6px 12px', backgroundColor: '#fef2f2', borderRadius: 8, display: 'inline-block' }}>
            <span style={{ fontSize: 12, fontWeight: 700, color: '#991b1b' }}>
              {status.deadLetterCount} dead letter{status.deadLetterCount > 1 ? 's' : ''}
            </span>
          </div>
        )}
      </div>

      {/* Manual Push */}
      <div style={{ marginBottom: 20 }}>
        <button data-testid="hmis-push-now" onClick={handlePush} disabled={pushing}
          style={{
            padding: '10px 20px', borderRadius: 10, border: 'none',
            backgroundColor: pushing ? '#6b7280' : '#1a56db', color: '#fff',
            fontSize: 14, fontWeight: 700, cursor: pushing ? 'default' : 'pointer',
          }}>
          {pushing ? '...' : intl.formatMessage({ id: 'hmis.pushNow' })}
        </button>
      </div>

      {/* Data Preview */}
      <div data-testid="hmis-preview" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <h3 style={{ fontSize: 15, fontWeight: 700, color: '#0f172a' }}>
            <FormattedMessage id="hmis.dataPreview" />
          </h3>
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => setDvFilter(null)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === null ? '#1a56db' : '#e2e8f0'}`,
              backgroundColor: dvFilter === null ? '#eff6ff' : '#fff', fontSize: 11, fontWeight: 600, cursor: 'pointer',
              color: dvFilter === null ? '#1a56db' : '#475569',
            }}>All</button>
            <button onClick={() => setDvFilter(false)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === false ? '#1a56db' : '#e2e8f0'}`,
              backgroundColor: dvFilter === false ? '#eff6ff' : '#fff', fontSize: 11, fontWeight: 600, cursor: 'pointer',
              color: dvFilter === false ? '#1a56db' : '#475569',
            }}>Non-DV</button>
            <button onClick={() => setDvFilter(true)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === true ? '#7c3aed' : '#e2e8f0'}`,
              backgroundColor: dvFilter === true ? '#f5f3ff' : '#fff', fontSize: 11, fontWeight: 600, cursor: 'pointer',
              color: dvFilter === true ? '#7c3aed' : '#475569',
            }}>DV (Aggregated)</button>
          </div>
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ borderBottom: '2px solid #e2e8f0', textAlign: 'left' }}>
              <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569' }}>Shelter</th>
              <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569' }}>Population</th>
              <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569', textAlign: 'right' }}>Total</th>
              <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569', textAlign: 'right' }}>Occupied</th>
              <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569', textAlign: 'right' }}>Util %</th>
            </tr>
          </thead>
          <tbody>
            {filteredPreview.map((r, i) => (
              <tr key={i} data-testid={`hmis-preview-row-${i}`} style={{
                borderBottom: '1px solid #f1f5f9',
                backgroundColor: r.isDvAggregated ? '#f5f3ff' : 'transparent',
              }}>
                <td style={{ padding: '8px 12px', fontWeight: r.isDvAggregated ? 700 : 400, color: r.isDvAggregated ? '#7c3aed' : '#0f172a' }}>
                  {r.projectName}
                </td>
                <td style={{ padding: '8px 12px', color: '#475569', textTransform: 'capitalize' }}>
                  {r.householdType.replace(/_/g, ' ').toLowerCase()}
                </td>
                <td style={{ padding: '8px 12px', textAlign: 'right', fontWeight: 600 }}>{r.bedInventory}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right' }}>{r.bedsOccupied}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right', color: r.utilizationPercent > 100 ? '#991b1b' : '#475569' }}>
                  {r.utilizationPercent.toFixed(1)}%
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Export History */}
      <div data-testid="hmis-history">
        <h3 style={{ fontSize: 15, fontWeight: 700, color: '#0f172a', marginBottom: 10 }}>
          <FormattedMessage id="hmis.exportHistory" />
        </h3>
        {history.length === 0 ? (
          <p style={{ fontSize: 14, color: '#475569' }}><FormattedMessage id="hmis.noHistory" /></p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr style={{ borderBottom: '2px solid #e2e8f0', textAlign: 'left' }}>
                <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569' }}>Time</th>
                <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569' }}>Vendor</th>
                <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569' }}>Records</th>
                <th style={{ padding: '8px 12px', fontWeight: 700, color: '#475569' }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {history.map((h, i) => (
                <tr key={i} style={{ borderBottom: '1px solid #f1f5f9' }}>
                  <td style={{ padding: '8px 12px' }}>{new Date(h.pushTimestamp).toLocaleString()}</td>
                  <td style={{ padding: '8px 12px' }}>{h.vendorType}</td>
                  <td style={{ padding: '8px 12px' }}>{h.recordCount}</td>
                  <td style={{ padding: '8px 12px' }}>
                    <span style={{
                      padding: '2px 8px', borderRadius: 6, fontSize: 11, fontWeight: 700,
                      backgroundColor: h.status === 'SUCCESS' ? '#f0fdf4' : '#fef2f2',
                      color: h.status === 'SUCCESS' ? '#166534' : '#991b1b',
                    }}>{h.status}</span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
