import { useState, useEffect, useCallback, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { AuthContext } from '../../../auth/AuthContext';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { tableStyle, thStyle, tdStyle } from '../styles';

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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setTestResult({ ok: false, text: apiErr.message || intl.formatMessage({ id: 'admin.oauth2.testFailed' }) });
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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setMessage({ type: 'error', text: apiErr.message || intl.formatMessage({ id: 'admin.oauth2.saveError' }) });
    } finally { setSubmitting(false); }
  };

  const handleDelete = async (id: string) => {
    try {
      await api.delete(`/api/v1/tenants/${tenantId}/oauth2-providers/${id}`);
      setMessage({ type: 'success', text: intl.formatMessage({ id: 'admin.oauth2.deleted' }) });
      setDeleteConfirm(null);
      loadProviders();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setMessage({ type: 'error', text: apiErr.message || intl.formatMessage({ id: 'admin.oauth2.saveError' }) });
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


export default OAuth2ProvidersTab;
