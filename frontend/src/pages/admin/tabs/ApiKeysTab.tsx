import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight, font } from '../../../theme/typography';
import { StatusBadge, RoleBadge, ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';
import type { ApiKeyRow, ApiKeyCreateResponse } from '../types';

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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
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

export default ApiKeysTab;
