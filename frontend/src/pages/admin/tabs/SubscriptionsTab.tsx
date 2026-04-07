import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight, font } from '../../../theme/typography';
import { StatusBadge, ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';
import type { SubscriptionRow } from '../types';

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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
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

export default SubscriptionsTab;
