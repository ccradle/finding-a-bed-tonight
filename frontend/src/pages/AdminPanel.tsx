import { useState, useEffect, useCallback, useContext, lazy, Suspense } from 'react';
import { Link } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';
import { UserEditDrawer } from '../components/UserEditDrawer';
import { AuthContext } from '../auth/AuthContext';
import { font, text, weight } from '../theme/typography';
import { color } from '../theme/colors';

// Lazy-load Analytics tab — ~200KB Recharts bundle only downloads when admin opens it.
const LazyAnalyticsTab = lazy(() => import('./AnalyticsTab'));

// --- Types ---

interface User {
  id: string;
  email: string;
  displayName: string;
  roles: string[];
  dvAccess: boolean;
  status: string;
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
    <div style={{ background: color.bg, borderRadius: 12, padding: 16, marginBottom: 16, border: `1px solid ${color.border}`, boxShadow: '0 1px 3px rgba(0,0,0,0.06)' }}
      data-testid="reservation-settings">
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <label htmlFor="hold-duration-input" style={{ fontSize: text.base, fontWeight: weight.semibold, color: color.text }}>
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
          style={{ width: 80, padding: '8px 12px', borderRadius: 8, border: `2px solid ${color.border}`, fontSize: text.base, textAlign: 'center', minHeight: 44 }}
        />
        <span style={{ fontSize: text.sm, color: color.textTertiary }}>
          <FormattedMessage id="admin.holdDuration.unit" defaultMessage="minutes" />
        </span>
        <button
          onClick={handleSave}
          disabled={saving}
          data-testid="hold-duration-save"
          style={{
            padding: '8px 16px', backgroundColor: color.primary, color: color.textInverse,
            border: 'none', borderRadius: 8, fontSize: text.sm, fontWeight: weight.bold,
            cursor: saving ? 'default' : 'pointer', minHeight: 44,
          }}
        >
          {saving ? '...' : intl.formatMessage({ id: 'common.save' })}
        </button>
        {message && (
          <span aria-live="polite" style={{ fontSize: text.sm, color: message.type === 'success' ? color.success : color.error, fontWeight: weight.semibold }}>
            {message.text}
          </span>
        )}
      </div>
      <p style={{ fontSize: text.xs, color: color.textTertiary, margin: '8px 0 0' }}>
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
        background: `linear-gradient(135deg, ${color.headerGradientStart} 0%, ${color.headerGradientMid} 50%, ${color.headerGradientEnd} 100%)`,
        borderRadius: 16, padding: '28px 24px', marginBottom: 20, color: color.textInverse,
        boxShadow: '0 4px 24px rgba(0,0,0,0.15)',
      }}>
        <h1 style={{ margin: 0, fontSize: text['2xl'], fontWeight: weight.extrabold, letterSpacing: '-0.03em' }}>
          <FormattedMessage id="admin.title" />
        </h1>
        <p style={{ margin: '6px 0 0', fontSize: text.base, color: color.textTertiary }}>
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
              fontSize: text.base, fontWeight: activeTab === tab.key ? weight.bold : weight.medium,
              color: activeTab === tab.key ? color.primary : color.textTertiary,
              borderBottom: activeTab === tab.key ? `3px solid ${color.primaryText}` : '3px solid transparent',
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
          <Suspense fallback={<div style={{ textAlign: 'center', padding: 40, color: color.textTertiary }}>Loading analytics...</div>}>
            <LazyAnalyticsTab />
          </Suspense>
        )}
      </div>
    </div>
  );
}

// --- Shared Styles ---

const tableStyle: React.CSSProperties = {
  width: '100%', borderCollapse: 'collapse', fontSize: text.base,
};

const thStyle: React.CSSProperties = {
  textAlign: 'left', padding: '10px 14px', fontWeight: weight.bold, color: color.text,
  borderBottom: `2px solid ${color.border}`, fontSize: text.xs, textTransform: 'uppercase',
  letterSpacing: '0.04em',
};

const tdStyle = (index: number): React.CSSProperties => ({
  padding: '12px 14px', borderBottom: `1px solid ${color.borderLight}`,
  backgroundColor: index % 2 === 0 ? color.bg : color.bgSecondary,
  color: color.text,
});

const primaryBtnStyle: React.CSSProperties = {
  padding: '12px 20px', backgroundColor: color.primary, color: color.textInverse,
  border: 'none', borderRadius: 10, fontSize: text.base, fontWeight: weight.bold,
  cursor: 'pointer', minHeight: 44,
};

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '12px 14px', borderRadius: 10,
  border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box',
  color: color.text, fontWeight: weight.medium, outline: 'none',
};

function StatusBadge({ active, yesId, noId }: { active: boolean; yesId: string; noId: string }) {
  return (
    <span style={{
      padding: '4px 10px', borderRadius: 6, fontSize: text.xs, fontWeight: weight.semibold,
      backgroundColor: active ? color.successBg : color.errorBg,
      color: active ? color.success : color.error,
      border: `1px solid ${active ? color.successBorder : color.errorBorder}`,
    }}>
      <FormattedMessage id={active ? yesId : noId} />
    </span>
  );
}

function RoleBadge({ role }: { role: string }) {
  return (
    <span style={{
      padding: '3px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.semibold,
      backgroundColor: color.bgHighlight, color: color.primaryHover, marginRight: 4,
      border: `1px solid ${color.primaryLight}`,
    }}>{role}</span>
  );
}

function ErrorBox({ message }: { message: string }) {
  return (
    <div style={{
      backgroundColor: color.errorBg, color: color.error, padding: '14px 18px',
      borderRadius: 12, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium,
    }}>{message}</div>
  );
}

function NoData() {
  return (
    <div style={{ textAlign: 'center', padding: 40, color: color.textMuted, fontSize: text.base, fontWeight: weight.medium }}>
      <FormattedMessage id="admin.noData" />
    </div>
  );
}

function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: color.textMuted }}>
      <div style={{
        width: 32, height: 32, border: `3px solid ${color.border}`, borderTopColor: color.primary,
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
  const [editUser, setEditUser] = useState<User | null>(null);
  const [resetUser, setResetUser] = useState<User | null>(null);
  const [resetPassword, setResetPassword] = useState('');
  const [resetConfirm, setResetConfirm] = useState('');
  const [resetError, setResetError] = useState<string | null>(null);
  const [resetSubmitting, setResetSubmitting] = useState(false);
  const [resetSuccess, setResetSuccess] = useState<string | null>(null);

  const handleResetPassword = async () => {
    if (!resetUser) return;
    setResetError(null);
    if (resetPassword !== resetConfirm) {
      setResetError(intl.formatMessage({ id: 'password.change.mismatch' }));
      return;
    }
    if (resetPassword.length < 12) {
      setResetError(intl.formatMessage({ id: 'password.change.tooShort' }));
      return;
    }
    setResetSubmitting(true);
    try {
      await api.post(`/api/v1/users/${resetUser.id}/reset-password`, { newPassword: resetPassword });
      setResetSuccess(intl.formatMessage({ id: 'password.reset.success' }, { username: resetUser.displayName }));
      setResetUser(null);
      setResetPassword('');
      setResetConfirm('');
    } catch (err: unknown) {
      const apiErr = err as { status?: number; message?: string };
      if (apiErr.status === 409) {
        setResetError(intl.formatMessage({ id: 'password.reset.ssoOnly' }));
      } else {
        setResetError(apiErr.message || intl.formatMessage({ id: 'password.reset.error' }));
      }
    } finally {
      setResetSubmitting(false);
    }
  };

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<User[]>('/api/v1/users');
      setUsers(data || []);
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
          padding: 20, border: `2px solid ${color.border}`, borderRadius: 14,
          marginBottom: 20, backgroundColor: color.bgSecondary,
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.email" />
              </label>
              <input value={formEmail} onChange={(e) => setFormEmail(e.target.value)}
                type="email" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.displayName" />
              </label>
              <input value={formDisplayName} onChange={(e) => setFormDisplayName(e.target.value)}
                style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                Password
              </label>
              <input value={formPassword} onChange={(e) => setFormPassword(e.target.value)}
                type="password" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.roles" />
              </label>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {ROLE_OPTIONS.map((role) => (
                  <button
                    key={role}
                    onClick={() => toggleRole(role)}
                    style={{
                      padding: '8px 14px', borderRadius: 8, border: `2px solid ${formRoles.includes(role) ? color.primary : color.border}`,
                      backgroundColor: formRoles.includes(role) ? color.bgHighlight : color.bg,
                      color: formRoles.includes(role) ? color.primary : color.textTertiary,
                      fontSize: text.sm, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 40,
                    }}
                  >{role}</button>
                ))}
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary }}>
                <FormattedMessage id="admin.dvAccess" />
              </label>
              <button
                onClick={() => setFormDvAccess(!formDvAccess)}
                style={{
                  padding: '6px 14px', borderRadius: 8, border: '2px solid',
                  borderColor: formDvAccess ? color.successBright : color.border,
                  backgroundColor: formDvAccess ? color.successBg : color.bg,
                  color: formDvAccess ? color.success : color.error,
                  fontSize: text.sm, fontWeight: weight.semibold, cursor: 'pointer',
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
                <th style={thStyle}><FormattedMessage id="admin.user.statusHeader" /></th>
                <th style={thStyle}></th>
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
                  <td style={tdStyle(i)}>
                    <StatusBadge
                      active={u.status !== 'DEACTIVATED'}
                      yesId="admin.user.statusActive"
                      noId="admin.user.statusDeactivated"
                    />
                  </td>
                  <td style={{ ...tdStyle(i), display: 'flex', gap: 6, flexWrap: 'nowrap' }}>
                    <button
                      onClick={() => setEditUser(u)}
                      data-testid={`edit-user-${u.email}`}
                      style={{
                        padding: '6px 12px',
                        backgroundColor: 'transparent',
                        color: color.primaryText,
                        border: `1px solid ${color.primaryText}`,
                        borderRadius: 6,
                        fontSize: text.xs,
                        fontWeight: weight.semibold,
                        cursor: 'pointer',
                        minHeight: 32,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <FormattedMessage id="admin.user.edit" />
                    </button>
                    <button
                      onClick={() => { setResetUser(u); setResetError(null); setResetSuccess(null); setResetPassword(''); setResetConfirm(''); }}
                      data-testid={`reset-password-${u.email}`}
                      style={{
                        padding: '6px 12px',
                        backgroundColor: 'transparent',
                        color: color.primaryText,
                        border: `1px solid ${color.primaryText}`,
                        borderRadius: 6,
                        fontSize: text.xs,
                        fontWeight: weight.semibold,
                        cursor: 'pointer',
                        minHeight: 32,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      <FormattedMessage id="password.reset.button" />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Reset success message */}
      {resetSuccess && (
        <div style={{ backgroundColor: color.successBg, color: color.success, padding: '12px 16px', borderRadius: 10, marginTop: 12, fontSize: text.base, fontWeight: weight.medium }}>
          {resetSuccess}
        </div>
      )}

      {/* Reset Password Modal */}
      {resetUser && (
        <div
          style={{ position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9000 }}
          onClick={(e) => { if (e.target === e.currentTarget) setResetUser(null); }}
        >
          <div
            role="dialog"
            aria-modal="true"
            aria-labelledby="reset-password-title"
            style={{ background: color.bg, borderRadius: 16, padding: 32, maxWidth: 420, width: '90%', boxShadow: '0 8px 32px rgba(0,0,0,0.2)' }}
          >
            <h3 id="reset-password-title" style={{ margin: '0 0 20px', fontSize: text.xl, fontWeight: weight.bold, color: color.text }}>
              <FormattedMessage id="password.reset.title" values={{ username: resetUser.displayName }} />
            </h3>
            {resetError && (
              <div role="alert" style={{ backgroundColor: color.errorBg, color: color.error, padding: '12px 16px', borderRadius: 10, marginBottom: 16, fontSize: text.base, fontWeight: weight.medium }}>
                {resetError}
              </div>
            )}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <div>
                <label htmlFor="reset-new-password" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                  <FormattedMessage id="password.reset.new" />
                </label>
                <input id="reset-new-password" type="password" autoComplete="new-password" value={resetPassword} onChange={(e) => setResetPassword(e.target.value)}
                  style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box', color: color.text, fontWeight: weight.medium, outline: 'none' }}
                  data-testid="reset-new-password-input" />
                <span style={{ fontSize: text.xs, color: color.textMuted, marginTop: 2, display: 'block' }}><FormattedMessage id="password.change.minLength" /></span>
              </div>
              <div>
                <label htmlFor="reset-confirm-password" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                  <FormattedMessage id="password.reset.confirm" />
                </label>
                <input id="reset-confirm-password" type="password" autoComplete="new-password" value={resetConfirm} onChange={(e) => setResetConfirm(e.target.value)}
                  style={{ width: '100%', padding: '12px 14px', borderRadius: 10, border: `2px solid ${color.border}`, fontSize: text.base, boxSizing: 'border-box', color: color.text, fontWeight: weight.medium, outline: 'none' }}
                  data-testid="reset-confirm-password-input" />
              </div>
              <div style={{ display: 'flex', gap: 10, marginTop: 8 }}>
                <button type="button" onClick={() => setResetUser(null)}
                  style={{ flex: 1, padding: '12px 16px', backgroundColor: color.bgTertiary, color: color.textSecondary, border: `1px solid ${color.border}`, borderRadius: 10, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44 }}>
                  <FormattedMessage id="password.cancel" />
                </button>
                <button type="button" onClick={handleResetPassword} disabled={resetSubmitting}
                  data-testid="reset-password-submit"
                  style={{ flex: 1, padding: '12px 16px', backgroundColor: color.primary, color: color.textInverse, border: 'none', borderRadius: 10, fontSize: text.base, fontWeight: weight.bold, cursor: resetSubmitting ? 'not-allowed' : 'pointer', minHeight: 44, opacity: resetSubmitting ? 0.7 : 1 }}>
                  {resetSubmitting ? <FormattedMessage id="password.submitting" /> : <FormattedMessage id="password.reset.submit" />}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* User Edit Drawer */}
      <UserEditDrawer
        user={editUser}
        onClose={() => setEditUser(null)}
        onSaved={() => { fetchUsers(); setEditUser(null); }}
      />
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
      setShelters(data || []);
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
                <th style={thStyle}></th>
              </tr>
            </thead>
            <tbody>
              {shelters.map((item, i) => (
                <tr key={item.shelter.id}>
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{item.shelter.name}</td>
                  <td style={tdStyle(i)}>{item.shelter.addressCity}</td>
                  <td style={tdStyle(i)}>
                    {item.availabilitySummary?.totalBedsAvailable != null
                      ? <span style={{ fontWeight: weight.bold, color: item.availabilitySummary.totalBedsAvailable > 0 ? color.success : color.error }}>
                          {item.availabilitySummary.totalBedsAvailable}
                        </span>
                      : <span style={{ color: color.textMuted }}>—</span>}
                  </td>
                  <td style={tdStyle(i)}>
                    {item.availabilitySummary
                      ? <span style={{
                          padding: '2px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.bold,
                          backgroundColor: item.availabilitySummary.dataFreshness === 'FRESH' ? color.successBg
                            : item.availabilitySummary.dataFreshness === 'AGING' ? color.warningBg
                            : item.availabilitySummary.dataFreshness === 'STALE' ? color.errorBg : color.borderLight,
                          color: item.availabilitySummary.dataFreshness === 'FRESH' ? color.success
                            : item.availabilitySummary.dataFreshness === 'AGING' ? color.warning
                            : item.availabilitySummary.dataFreshness === 'STALE' ? color.error : color.textTertiary,
                        }}>{item.availabilitySummary.dataFreshness}</span>
                      : <span style={{ color: color.textMuted }}>—</span>}
                  </td>
                  <td style={tdStyle(i)}>
                    <DataAge dataAgeSeconds={item.shelter.updatedAt ? Math.floor((Date.now() - new Date(item.shelter.updatedAt).getTime()) / 1000) : null} />
                  </td>
                  <td style={tdStyle(i)}>
                    <a
                      href={`/coordinator/shelters/${item.shelter.id}/edit?from=/admin`}
                      data-testid={`edit-shelter-${item.shelter.id}`}
                      style={{
                        color: color.primaryText,
                        fontSize: text.sm,
                        fontWeight: weight.semibold,
                        textDecoration: 'none',
                      }}
                    >
                      <FormattedMessage id="shelter.editBtn" />
                    </a>
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
      setKeys(data || []);
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
          padding: 20, backgroundColor: color.warningBg, border: `2px solid ${color.warningBright}`,
          borderRadius: 14, marginBottom: 20,
        }}>
          <div style={{ fontSize: text.sm, fontWeight: weight.bold, color: color.warning, marginBottom: 8 }}>
            <FormattedMessage id="admin.keyWarning" />
          </div>
          <div data-testid="api-key-reveal" style={{
            padding: '12px 14px', backgroundColor: color.bg, borderRadius: 8,
            fontFamily: font.mono, fontSize: text.base, color: color.text, wordBreak: 'break-all',
            marginBottom: 10, border: `1px solid ${color.border}`,
          }}>
            {newKeyResult.plaintextKey}
          </div>
          <button onClick={copyKey} style={{
            ...primaryBtnStyle,
            backgroundColor: copied ? color.successBright : color.primary,
          }}>
            {copied ? 'Copied!' : 'Copy'}
          </button>
          <button onClick={() => setNewKeyResult(null)} style={{
            marginLeft: 8, padding: '12px 20px', backgroundColor: color.borderLight,
            color: color.textTertiary, border: 'none', borderRadius: 10, fontSize: text.base,
            fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
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
          padding: 20, border: `2px solid ${color.border}`, borderRadius: 14,
          marginBottom: 20, backgroundColor: color.bgSecondary,
        }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
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
                  <td style={{ ...tdStyle(i), fontFamily: font.mono }}>****{k.suffix}</td>
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
      setImports(data || []);
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
        <Link to="/coordinator/import/hsds" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>HSDS Import</Link>
        <Link to="/coordinator/import/211" style={{
          ...primaryBtnStyle, textDecoration: 'none',
          display: 'inline-flex', alignItems: 'center',
        }}>2-1-1 Import</Link>
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
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{imp.importType}</td>
                  <td style={tdStyle(i)}>{imp.filename}</td>
                  <td style={{ ...tdStyle(i), color: color.success, fontWeight: weight.semibold }}>{imp.created}</td>
                  <td style={{ ...tdStyle(i), color: color.primaryText, fontWeight: weight.semibold }}>{imp.updated}</td>
                  <td style={{ ...tdStyle(i), color: color.warning }}>{imp.skipped}</td>
                  <td style={{
                    ...tdStyle(i),
                    color: imp.errors > 0 ? color.error : color.textTertiary,
                    fontWeight: imp.errors > 0 ? weight.bold : weight.normal,
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
      setSubs(data || []);
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
          padding: 20, border: `2px solid ${color.border}`, borderRadius: 14,
          marginBottom: 20, backgroundColor: color.bgSecondary,
        }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                Event Type
              </label>
              <input value={formEventType} onChange={(e) => setFormEventType(e.target.value)}
                style={inputStyle} placeholder="e.g. shelter.updated" />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
                Callback URL
              </label>
              <input value={formCallbackUrl} onChange={(e) => setFormCallbackUrl(e.target.value)}
                type="url" style={inputStyle} placeholder="https://..." />
            </div>
            <div>
              <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, marginBottom: 4, display: 'block' }}>
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
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{s.eventType}</td>
                  <td style={{ ...tdStyle(i), fontFamily: font.mono, fontSize: text.sm }}>{s.callbackUrl}</td>
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
      setSurges(data || []);
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
          background: `linear-gradient(135deg, ${color.errorMid} 0%, ${color.error} 100%)`, color: color.textInverse,
        }}>
          <div style={{ fontSize: text.base, fontWeight: weight.extrabold, letterSpacing: '0.06em', marginBottom: 6 }}>
            <FormattedMessage id="surge.banner" />
          </div>
          <div style={{ fontSize: text.md, fontWeight: weight.medium, marginBottom: 8 }}>{activeSurge.reason}</div>
          <div style={{ fontSize: text.xs, opacity: 0.85, marginBottom: 12 }}>
            <FormattedMessage id="surge.since" />: {new Date(activeSurge.activatedAt).toLocaleString()}
          </div>
          <button onClick={() => deactivateSurge(activeSurge.id)} style={{
            padding: '10px 20px', borderRadius: 8, border: '2px solid rgba(255,255,255,0.5)',
            backgroundColor: 'transparent', color: color.textInverse, fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer',
          }}><FormattedMessage id="surge.deactivate" /></button>
        </div>
      )}

      {!activeSurge && (
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
          <button onClick={() => setShowForm(!showForm)} style={{ ...primaryBtnStyle, backgroundColor: color.errorMid }}>
            <FormattedMessage id="surge.activate" />
          </button>
        </div>
      )}

      {showForm && (
        <div style={{ padding: 20, border: `2px solid ${color.errorBorder}`, borderRadius: 14, marginBottom: 20, backgroundColor: color.errorBg }}>
          <div style={{ marginBottom: 12 }}>
            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="surge.reason" />
            </label>
            <input value={reason} onChange={e => setReason(e.target.value)}
              style={inputStyle} placeholder={intl.formatMessage({ id: 'surge.reasonPlaceholder' })} />
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="surge.scheduledEnd" />
            </label>
            <input type="datetime-local" value={scheduledEnd} onChange={e => setScheduledEnd(e.target.value)} style={inputStyle} />
          </div>
          <button onClick={activateSurge} disabled={submitting || !reason}
            style={{ ...primaryBtnStyle, backgroundColor: color.errorMid, width: '100%', opacity: submitting || !reason ? 0.6 : 1 }}>
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
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{s.reason}</td>
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
    background: active ? color.primary : color.borderMedium, position: 'relative' as const, transition: 'background 0.2s',
  });
  const toggleDot = (active: boolean): React.CSSProperties => ({
    position: 'absolute' as const, top: 3, left: active ? 24 : 3,
    width: 20, height: 20, borderRadius: 10, background: color.bg,
    transition: 'left 0.2s', boxShadow: '0 1px 2px rgba(0,0,0,0.2)',
  });
  const inputStyle: React.CSSProperties = {
    padding: '8px 12px', border: `1px solid ${color.borderMedium}`, borderRadius: 8, fontSize: text.base, width: 120,
  };
  const sectionStyle: React.CSSProperties = {
    background: color.bg, borderRadius: 12, padding: 20,
    boxShadow: '0 1px 3px rgba(0,0,0,0.08)', marginBottom: 16,
  };
  const rowStyle: React.CSSProperties = {
    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
    padding: '12px 0', borderBottom: `1px solid ${color.border}`,
  };

  return (
    <div>
      {/* Temperature Status Banner */}
      {tempStatus && typeof tempStatus.temperatureF === 'number' && (
        <div style={{
          ...sectionStyle,
          background: tempStatus.gapDetected
            ? `linear-gradient(135deg, ${color.warningBg} 0%, ${color.warningBright} 100%)`
            : `linear-gradient(135deg, ${color.successBg} 0%, ${color.successBorder} 100%)`,
          border: tempStatus.gapDetected ? `2px solid ${color.warningMid}` : `2px solid ${color.successBright}`,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: text['3xl'], fontWeight: weight.extrabold }}>
              {tempStatus.temperatureF.toFixed(1)}°F
            </span>
            <div>
              <div style={{ fontWeight: weight.semibold, fontSize: text.base }}>
                <FormattedMessage id="admin.observability.station" /> {tempStatus.stationId || 'Unknown'}
              </div>
              <div style={{ fontSize: text.sm, color: color.textTertiary }}>
                <FormattedMessage id="admin.observability.threshold" />: {tempStatus.thresholdF}°F
                {tempStatus.surgeActive && <span> · <FormattedMessage id="admin.observability.surgeActive" /></span>}
              </div>
            </div>
          </div>
          {tempStatus.gapDetected && (
            <div style={{
              marginTop: 10, padding: '8px 12px', background: 'rgba(245,158,11,0.15)',
              borderRadius: 8, fontSize: text.sm, fontWeight: weight.semibold, color: color.warning,
            }}>
              {tempStatus.temperatureF.toFixed(0)}°F — <FormattedMessage id="admin.observability.belowThreshold"
                values={{ threshold: tempStatus.thresholdF.toString() }} />.{' '}
              <FormattedMessage id="admin.observability.considerSurge" />
            </div>
          )}
          {tempStatus.lastChecked && (
            <div style={{ fontSize: text.xs, color: color.textTertiary, marginTop: 6 }}>
              <FormattedMessage id="admin.observability.lastChecked" />: {new Date(tempStatus.lastChecked).toLocaleString()}
            </div>
          )}
        </div>
      )}

      {/* Configuration */}
      <div style={sectionStyle}>
        <h3 style={{ margin: '0 0 16px', fontSize: text.md, fontWeight: weight.bold }}>
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
          <div style={{ padding: '12px 0', borderBottom: `1px solid ${color.border}` }}>
            <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
              <FormattedMessage id="admin.observability.tracingEndpoint" />
            </label>
            <input type="text" value={config.tracingEndpoint}
              onChange={e => setConfig(c => ({ ...c, tracingEndpoint: e.target.value }))}
              style={{ ...inputStyle, width: '100%' }} />
          </div>
        )}

        <div style={{ padding: '16px 0 8px' }}>
          <h4 style={{ margin: '0 0 12px', fontSize: text.base, fontWeight: weight.semibold, color: color.textTertiary }}>
            <FormattedMessage id="admin.observability.intervals" />
          </h4>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.staleInterval" />
              </label>
              <input id="stale-interval" type="number" min={1} value={config.monitorStaleIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorStaleIntervalMinutes: parseInt(e.target.value) || 5 }))}
                aria-label="Stale shelter check interval in minutes"
                style={inputStyle} />
              <span style={{ fontSize: text.xs, color: color.textMuted, marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.dvCanaryInterval" />
              </label>
              <input id="dv-canary-interval" type="number" min={1} value={config.monitorDvCanaryIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorDvCanaryIntervalMinutes: parseInt(e.target.value) || 15 }))}
                aria-label="DV canary check interval in minutes"
                style={inputStyle} />
              <span style={{ fontSize: text.xs, color: color.textMuted, marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.tempInterval" />
              </label>
              <input id="temp-interval" type="number" min={1} value={config.monitorTemperatureIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorTemperatureIntervalMinutes: parseInt(e.target.value) || 60 }))}
                aria-label="Temperature check interval in minutes"
                style={inputStyle} />
              <span style={{ fontSize: text.xs, color: color.textMuted, marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.tempThreshold" />
              </label>
              <input id="temp-threshold" data-testid="temp-threshold" type="number" value={config.temperatureThresholdF}
                onChange={e => setConfig(c => ({ ...c, temperatureThresholdF: parseFloat(e.target.value) || 32 }))}
                aria-label="Temperature threshold in Fahrenheit"
                style={inputStyle} />
              <span style={{ fontSize: text.xs, color: color.textMuted, marginLeft: 4 }}>°F</span>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 16 }}>
          <button onClick={handleSave} disabled={saving}
            data-testid="observability-save"
            style={{
              padding: '10px 24px', background: color.primary, color: color.textInverse, border: 'none',
              borderRadius: 8, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer',
              opacity: saving ? 0.6 : 1, minHeight: 44,
            }}>
            {saving ? '...' : intl.formatMessage({ id: 'admin.observability.save' })}
          </button>
          {message && (
            <span style={{ fontSize: text.sm, fontWeight: weight.semibold,
              color: message.type === 'success' ? color.successBright : color.errorMid }}>
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
      setProviders(data || []);
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
    background: color.bg, borderRadius: 12, padding: 20,
    boxShadow: '0 1px 3px rgba(0,0,0,0.08)', marginBottom: 16,
  };
  const inputStyle: React.CSSProperties = {
    width: '100%', padding: '10px 12px', border: `1px solid ${color.borderMedium}`, borderRadius: 8,
    fontSize: text.base, boxSizing: 'border-box' as const, marginBottom: 12,
  };

  return (
    <div>
      <div style={sectionStyle}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
          <h3 style={{ margin: 0, fontSize: text.md, fontWeight: weight.bold }}>
            <FormattedMessage id="admin.oauth2.title" />
          </h3>
          {!showForm && (
            <button onClick={() => { resetForm(); setShowForm(true); }}
              style={{ padding: '8px 16px', background: color.primary, color: color.textInverse, border: 'none',
                borderRadius: 8, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer' }}>
              <FormattedMessage id="admin.oauth2.addProvider" />
            </button>
          )}
        </div>

        {message && (
          <div style={{
            padding: '10px 14px', borderRadius: 8, marginBottom: 12, fontSize: text.sm, fontWeight: weight.semibold,
            background: message.type === 'success' ? color.successBg : color.errorBg,
            color: message.type === 'success' ? color.success : color.error,
          }}>{message.text}</div>
        )}

        {/* Add/Edit Form */}
        {showForm && (
          <div style={{ background: color.bgSecondary, borderRadius: 8, padding: 16, marginBottom: 16, border: `1px solid ${color.border}` }}>
            {!editId && (
              <div style={{ marginBottom: 12 }}>
                <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
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
                <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>Provider Name</label>
                <input type="text" value={formName} onChange={e => setFormName(e.target.value)}
                  placeholder={providerType === 'microsoft' ? 'microsoft' : 'my-idp'}
                  style={inputStyle} />
              </div>
            )}

            <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
              <FormattedMessage id="admin.oauth2.clientId" />
            </label>
            <input type="text" value={formClientId} onChange={e => setFormClientId(e.target.value)}
              placeholder="your-client-id" style={inputStyle} />

            <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
              <FormattedMessage id="admin.oauth2.clientSecret" />
            </label>
            <input type="password" value={formClientSecret} onChange={e => setFormClientSecret(e.target.value)}
              placeholder={editId ? intl.formatMessage({ id: 'admin.oauth2.updateSecret' }) : ''}
              style={inputStyle} />
            <div style={{ fontSize: text.xs, color: color.textMuted, marginTop: -8, marginBottom: 12 }}>
              <FormattedMessage id="admin.oauth2.secretNote" />
            </div>

            <label style={{ display: 'block', fontSize: text.sm, color: color.textTertiary, marginBottom: 4 }}>
              <FormattedMessage id="admin.oauth2.issuerUri" />
            </label>
            <div style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <input type="text" value={formIssuerUri} onChange={e => setFormIssuerUri(e.target.value)}
                placeholder="https://accounts.google.com" style={{ ...inputStyle, flex: 1, marginBottom: 0 }} />
              <button onClick={handleTestConnection}
                style={{ padding: '8px 14px', background: color.borderLight, border: `1px solid ${color.borderMedium}`,
                  borderRadius: 8, fontSize: text.sm, cursor: 'pointer', whiteSpace: 'nowrap' as const }}>
                <FormattedMessage id="admin.oauth2.testConnection" />
              </button>
            </div>
            {testResult && (
              <div style={{ fontSize: text.sm, fontWeight: weight.semibold, marginBottom: 12,
                color: testResult.ok ? color.successBright : color.errorMid }}>
                {testResult.text}
              </div>
            )}

            <div style={{ display: 'flex', gap: 8 }}>
              <button onClick={handleSave} disabled={submitting}
                style={{ padding: '10px 20px', background: color.primary, color: color.textInverse, border: 'none',
                  borderRadius: 8, fontSize: text.base, fontWeight: weight.semibold, cursor: 'pointer',
                  opacity: submitting ? 0.6 : 1 }}>
                {submitting ? '...' : editId ? 'Update' : 'Save'}
              </button>
              <button onClick={resetForm}
                style={{ padding: '10px 20px', background: color.borderLight, color: color.textTertiary, border: `1px solid ${color.borderMedium}`,
                  borderRadius: 8, fontSize: text.base, cursor: 'pointer' }}>
                <FormattedMessage id="admin.cancel" />
              </button>
            </div>
          </div>
        )}

        {/* Provider List */}
        {providers.length === 0 && !showForm ? (
          <div style={{ textAlign: 'center', padding: 32, color: color.textMuted, fontSize: text.base }}>
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
                  <td style={{ ...tdStyle(i), fontWeight: weight.semibold }}>{p.providerName}</td>
                  <td style={tdStyle(i)}>
                    <button onClick={() => handleToggleEnabled(p)}
                      style={{ padding: '2px 10px', borderRadius: 12, border: 'none', fontSize: text.xs, fontWeight: weight.semibold,
                        cursor: 'pointer',
                        background: p.enabled ? color.successBg : color.borderLight,
                        color: p.enabled ? color.success : color.textTertiary }}>
                      {p.enabled ? 'Active' : 'Inactive'}
                    </button>
                  </td>
                  <td style={{ ...tdStyle(i), fontSize: text.xs, color: color.textTertiary, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis' }}>{p.issuerUri}</td>
                  <td style={tdStyle(i)}>{new Date(p.createdAt).toLocaleDateString()}</td>
                  <td style={tdStyle(i)}>
                    <button onClick={() => startEdit(p)}
                      style={{ marginRight: 8, padding: '4px 10px', fontSize: text.xs, border: `1px solid ${color.borderMedium}`,
                        borderRadius: 6, background: color.bg, cursor: 'pointer' }}>Edit</button>
                    <button onClick={() => setDeleteConfirm(p.id)}
                      style={{ padding: '4px 10px', fontSize: text.xs, border: `1px solid ${color.errorBorder}`,
                        borderRadius: 6, background: color.bg, color: color.errorMid, cursor: 'pointer' }}>Delete</button>
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
            <div style={{ background: color.bg, borderRadius: 12, padding: 24, maxWidth: 400, boxShadow: '0 8px 32px rgba(0,0,0,0.2)' }}>
              <p style={{ fontSize: text.base, marginBottom: 16 }}>
                <FormattedMessage id="admin.oauth2.deleteConfirm"
                  values={{ name: providers.find(p => p.id === deleteConfirm)?.providerName || '' }} />
              </p>
              <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
                <button onClick={() => setDeleteConfirm(null)}
                  style={{ padding: '8px 16px', background: color.borderLight, border: `1px solid ${color.borderMedium}`,
                    borderRadius: 8, cursor: 'pointer' }}>
                  <FormattedMessage id="admin.cancel" />
                </button>
                <button onClick={() => handleDelete(deleteConfirm)}
                  style={{ padding: '8px 16px', background: color.errorMid, color: color.textInverse, border: 'none',
                    borderRadius: 8, cursor: 'pointer', fontWeight: weight.semibold }}>Delete</button>
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

interface HmisVendorStatus {
  type: string;
  enabled: boolean;
  pushIntervalHours?: number;
}

interface HmisStatus {
  vendors: HmisVendorStatus[];
  deadLetterCount: number;
}

function HmisExportTab() {
  const intl = useIntl();
  const [preview, setPreview] = useState<HmisInventoryRecord[]>([]);
  const [history, setHistory] = useState<HmisAuditEntry[]>([]);
  const [status, setStatus] = useState<HmisStatus | null>(null);
  const [loading, setLoading] = useState(true);
  const [pushing, setPushing] = useState(false);
  const [dvFilter, setDvFilter] = useState<boolean | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const [statusData, previewData, historyData] = await Promise.all([
        api.get<HmisStatus>('/api/v1/hmis/status'),
        api.get<HmisInventoryRecord[]>('/api/v1/hmis/preview'),
        api.get<HmisAuditEntry[]>('/api/v1/hmis/history?limit=20'),
      ]);
      setStatus(statusData);
      setPreview(previewData || []);
      setHistory(historyData || []);
    } catch { /* silent */ }
    setLoading(false);
  }, []);

  // eslint-disable-next-line react-hooks/set-state-in-effect -- Initial data fetch on mount; setState in async callback is the standard pattern
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

  if (loading) return <div style={{ padding: 20, color: color.textTertiary }}><FormattedMessage id="coord.loading" /></div>;

  return (
    <div>
      {/* Export Status */}
      <div data-testid="hmis-status" style={{ marginBottom: 20 }}>
        <h3 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginBottom: 10 }}>
          <FormattedMessage id="hmis.exportStatus" />
        </h3>
        {status && status.vendors?.length > 0 ? (
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            {status.vendors.map((v: HmisVendorStatus, i: number) => (
              <div key={i} style={{
                padding: '10px 16px', borderRadius: 10, border: `1px solid ${color.border}`,
                backgroundColor: v.enabled ? color.successBg : color.errorBg,
              }}>
                <span style={{ fontWeight: weight.bold, fontSize: text.sm }}>{v.type}</span>
                <span style={{ marginLeft: 8, fontSize: text.xs, color: v.enabled ? color.success : color.error }}>
                  {v.enabled ? 'Enabled' : 'Disabled'}
                </span>
                <span style={{ marginLeft: 8, fontSize: text['2xs'], color: color.textTertiary }}>
                  every {v.pushIntervalHours}h
                </span>
              </div>
            ))}
          </div>
        ) : (
          <p style={{ fontSize: text.base, color: color.textTertiary }}><FormattedMessage id="hmis.noVendors" /></p>
        )}
        {status && status.deadLetterCount != null && status.deadLetterCount > 0 && (
          <div style={{ marginTop: 8, padding: '6px 12px', backgroundColor: color.errorBg, borderRadius: 8, display: 'inline-block' }}>
            <span style={{ fontSize: text.xs, fontWeight: weight.bold, color: color.error }}>
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
            backgroundColor: pushing ? color.textMuted : color.primary, color: color.textInverse,
            fontSize: text.base, fontWeight: weight.bold, cursor: pushing ? 'default' : 'pointer',
          }}>
          {pushing ? '...' : intl.formatMessage({ id: 'hmis.pushNow' })}
        </button>
      </div>

      {/* Data Preview */}
      <div data-testid="hmis-preview" style={{ marginBottom: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
          <h3 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text }}>
            <FormattedMessage id="hmis.dataPreview" />
          </h3>
          <div style={{ display: 'flex', gap: 6 }}>
            <button onClick={() => setDvFilter(null)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === null ? color.primary : color.border}`,
              backgroundColor: dvFilter === null ? color.bgHighlight : color.bg, fontSize: text['2xs'], fontWeight: weight.semibold, cursor: 'pointer',
              color: dvFilter === null ? color.primary : color.textTertiary,
            }}>All</button>
            <button onClick={() => setDvFilter(false)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === false ? color.primary : color.border}`,
              backgroundColor: dvFilter === false ? color.bgHighlight : color.bg, fontSize: text['2xs'], fontWeight: weight.semibold, cursor: 'pointer',
              color: dvFilter === false ? color.primary : color.textTertiary,
            }}>Non-DV</button>
            <button onClick={() => setDvFilter(true)} style={{
              padding: '4px 10px', borderRadius: 6, border: `1px solid ${dvFilter === true ? color.dv : color.border}`,
              backgroundColor: dvFilter === true ? color.dvBg : color.bg, fontSize: text['2xs'], fontWeight: weight.semibold, cursor: 'pointer',
              color: dvFilter === true ? color.dv : color.textTertiary,
            }}>DV (Aggregated)</button>
          </div>
        </div>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.sm }}>
          <thead>
            <tr style={{ borderBottom: `2px solid ${color.border}`, textAlign: 'left' }}>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Shelter</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Population</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary, textAlign: 'right' }}>Total</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary, textAlign: 'right' }}>Occupied</th>
              <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary, textAlign: 'right' }}>Util %</th>
            </tr>
          </thead>
          <tbody>
            {filteredPreview.map((r, i) => (
              <tr key={i} data-testid={`hmis-preview-row-${i}`} style={{
                borderBottom: `1px solid ${color.borderLight}`,
                backgroundColor: r.isDvAggregated ? color.dvBg : 'transparent',
              }}>
                <td style={{ padding: '8px 12px', fontWeight: r.isDvAggregated ? weight.bold : weight.normal, color: r.isDvAggregated ? color.dv : color.text }}>
                  {r.projectName}
                </td>
                <td style={{ padding: '8px 12px', color: color.textTertiary, textTransform: 'capitalize' }}>
                  {r.householdType.replace(/_/g, ' ').toLowerCase()}
                </td>
                <td style={{ padding: '8px 12px', textAlign: 'right', fontWeight: weight.semibold }}>{r.bedInventory}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right' }}>{r.bedsOccupied}</td>
                <td style={{ padding: '8px 12px', textAlign: 'right', color: r.utilizationPercent > 100 ? color.error : color.textTertiary }}>
                  {(r.utilizationPercent ?? 0).toFixed(1)}%
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Export History */}
      <div data-testid="hmis-history">
        <h3 style={{ fontSize: text.base, fontWeight: weight.bold, color: color.text, marginBottom: 10 }}>
          <FormattedMessage id="hmis.exportHistory" />
        </h3>
        {history.length === 0 ? (
          <p style={{ fontSize: text.base, color: color.textTertiary }}><FormattedMessage id="hmis.noHistory" /></p>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: text.sm }}>
            <thead>
              <tr style={{ borderBottom: `2px solid ${color.border}`, textAlign: 'left' }}>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Time</th>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Vendor</th>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Records</th>
                <th style={{ padding: '8px 12px', fontWeight: weight.bold, color: color.textTertiary }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {history.map((h, i) => (
                <tr key={i} style={{ borderBottom: `1px solid ${color.borderLight}` }}>
                  <td style={{ padding: '8px 12px' }}>{new Date(h.pushTimestamp).toLocaleString()}</td>
                  <td style={{ padding: '8px 12px' }}>{h.vendorType}</td>
                  <td style={{ padding: '8px 12px' }}>{h.recordCount}</td>
                  <td style={{ padding: '8px 12px' }}>
                    <span style={{
                      padding: '2px 8px', borderRadius: 6, fontSize: text['2xs'], fontWeight: weight.bold,
                      backgroundColor: h.status === 'SUCCESS' ? color.successBg : color.errorBg,
                      color: h.status === 'SUCCESS' ? color.success : color.error,
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
