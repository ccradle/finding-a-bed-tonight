import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { StatusBadge, ErrorBox, NoData, Spinner } from '../components';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';

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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'surge.alreadyActive' }));
    } finally {
      setSubmitting(false);
    }
  };

  const deactivateSurge = async (id: string) => {
    try {
      await api.patch(`/api/v1/surge-events/${id}/deactivate`);
      await fetchSurges();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'coord.error' }));
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
          <div style={{ fontSize: text.xs, color: color.textInverse, marginBottom: 12 }}>
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


export default SurgeTab;
