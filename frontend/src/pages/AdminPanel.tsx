import { useState, useEffect, useCallback, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../services/api';
import { DataAge } from '../components/DataAge';
import { AuthContext } from '../auth/AuthContext';

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

type TabKey = 'users' | 'shelters' | 'apiKeys' | 'imports' | 'subscriptions' | 'surge' | 'observability';

const TABS: { key: TabKey; labelId: string }[] = [
  { key: 'users', labelId: 'admin.users' },
  { key: 'shelters', labelId: 'admin.shelters' },
  { key: 'surge', labelId: 'admin.surge' },
  { key: 'apiKeys', labelId: 'admin.apiKeys' },
  { key: 'imports', labelId: 'admin.imports' },
  { key: 'subscriptions', labelId: 'admin.subscriptions' },
  { key: 'observability', labelId: 'admin.observability' },
];

const ROLE_OPTIONS = ['PLATFORM_ADMIN', 'COC_ADMIN', 'COORDINATOR', 'OUTREACH_WORKER'];

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

      {/* Tab bar */}
      <div style={{
        display: 'flex', overflowX: 'auto', gap: 0,
        borderBottom: '2px solid #e2e8f0', marginBottom: 20,
        WebkitOverflowScrolling: 'touch',
      }}>
        {TABS.map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            style={{
              padding: '12px 18px', minHeight: 44, whiteSpace: 'nowrap',
              border: 'none', backgroundColor: 'transparent', cursor: 'pointer',
              fontSize: 14, fontWeight: activeTab === tab.key ? 700 : 500,
              color: activeTab === tab.key ? '#1a56db' : '#64748b',
              borderBottom: activeTab === tab.key ? '3px solid #1a56db' : '3px solid transparent',
              marginBottom: -2, transition: 'color 0.12s, border-color 0.12s',
            }}
          >
            <FormattedMessage id={tab.labelId} />
          </button>
        ))}
      </div>

      {/* Tab content */}
      {activeTab === 'users' && <UsersTab />}
      {activeTab === 'shelters' && <SheltersTab />}
      {activeTab === 'apiKeys' && <ApiKeysTab />}
      {activeTab === 'imports' && <ImportsTab />}
      {activeTab === 'subscriptions' && <SubscriptionsTab />}
      {activeTab === 'surge' && <SurgeTab />}
      {activeTab === 'observability' && <ObservabilityTab />}
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
    <div style={{ textAlign: 'center', padding: 40, color: '#94a3b8', fontSize: 14, fontWeight: 500 }}>
      <FormattedMessage id="admin.noData" />
    </div>
  );
}

function Spinner() {
  return (
    <div style={{ textAlign: 'center', padding: 32, color: '#94a3b8' }}>
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
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.email" />
              </label>
              <input value={formEmail} onChange={(e) => setFormEmail(e.target.value)}
                type="email" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
                <FormattedMessage id="admin.displayName" />
              </label>
              <input value={formDisplayName} onChange={(e) => setFormDisplayName(e.target.value)}
                style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
                Password
              </label>
              <input value={formPassword} onChange={(e) => setFormPassword(e.target.value)}
                type="password" style={inputStyle} />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
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
                      color: formRoles.includes(role) ? '#1a56db' : '#64748b',
                      fontSize: 13, fontWeight: 600, cursor: 'pointer', minHeight: 40,
                    }}
                  >{role}</button>
                ))}
              </div>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b' }}>
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
                      : <span style={{ color: '#94a3b8' }}>—</span>}
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
                            : item.availabilitySummary.dataFreshness === 'STALE' ? '#991b1b' : '#64748b',
                        }}>{item.availabilitySummary.dataFreshness}</span>
                      : <span style={{ color: '#94a3b8' }}>—</span>}
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
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
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
                    color: imp.errors > 0 ? '#991b1b' : '#64748b',
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
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
                Event Type
              </label>
              <input value={formEventType} onChange={(e) => setFormEventType(e.target.value)}
                style={inputStyle} placeholder="e.g. shelter.updated" />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
                Callback URL
              </label>
              <input value={formCallbackUrl} onChange={(e) => setFormCallbackUrl(e.target.value)}
                type="url" style={inputStyle} placeholder="https://..." />
            </div>
            <div>
              <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', marginBottom: 4, display: 'block' }}>
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
            <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', display: 'block', marginBottom: 4 }}>
              <FormattedMessage id="surge.reason" />
            </label>
            <input value={reason} onChange={e => setReason(e.target.value)}
              style={inputStyle} placeholder={intl.formatMessage({ id: 'surge.reasonPlaceholder' })} />
          </div>
          <div style={{ marginBottom: 12 }}>
            <label style={{ fontSize: 12, fontWeight: 600, color: '#64748b', display: 'block', marginBottom: 4 }}>
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
            <div style={{ fontSize: 12, color: '#64748b', marginTop: 6 }}>
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
            style={toggleBtn(config.prometheusEnabled)}>
            <span style={toggleDot(config.prometheusEnabled)} />
          </button>
        </div>

        <div style={rowStyle}>
          <span><FormattedMessage id="admin.observability.tracing" /></span>
          <button onClick={() => setConfig(c => ({ ...c, tracingEnabled: !c.tracingEnabled }))}
            style={toggleBtn(config.tracingEnabled)}>
            <span style={toggleDot(config.tracingEnabled)} />
          </button>
        </div>

        {config.tracingEnabled && (
          <div style={{ padding: '12px 0', borderBottom: '1px solid #e2e8f0' }}>
            <label style={{ display: 'block', fontSize: 13, color: '#64748b', marginBottom: 4 }}>
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
              <label style={{ display: 'block', fontSize: 13, color: '#64748b', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.staleInterval" />
              </label>
              <input type="number" min={1} value={config.monitorStaleIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorStaleIntervalMinutes: parseInt(e.target.value) || 5 }))}
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#94a3b8', marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#64748b', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.dvCanaryInterval" />
              </label>
              <input type="number" min={1} value={config.monitorDvCanaryIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorDvCanaryIntervalMinutes: parseInt(e.target.value) || 15 }))}
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#94a3b8', marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#64748b', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.tempInterval" />
              </label>
              <input type="number" min={1} value={config.monitorTemperatureIntervalMinutes}
                onChange={e => setConfig(c => ({ ...c, monitorTemperatureIntervalMinutes: parseInt(e.target.value) || 60 }))}
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#94a3b8', marginLeft: 4 }}>min</span>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: 13, color: '#64748b', marginBottom: 4 }}>
                <FormattedMessage id="admin.observability.tempThreshold" />
              </label>
              <input type="number" value={config.temperatureThresholdF}
                onChange={e => setConfig(c => ({ ...c, temperatureThresholdF: parseFloat(e.target.value) || 32 }))}
                style={inputStyle} />
              <span style={{ fontSize: 12, color: '#94a3b8', marginLeft: 4 }}>°F</span>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 16 }}>
          <button onClick={handleSave} disabled={saving}
            style={{
              padding: '10px 24px', background: '#1a56db', color: '#fff', border: 'none',
              borderRadius: 8, fontSize: 14, fontWeight: 600, cursor: 'pointer',
              opacity: saving ? 0.6 : 1,
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
