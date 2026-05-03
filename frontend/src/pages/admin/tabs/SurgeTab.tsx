import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { StatusBadge, ErrorBox, NoData, Spinner } from '../components';
import { SurgeTemperatureSettings } from '../components/SurgeTemperatureSettings';
import { tableStyle, thStyle, tdStyle, primaryBtnStyle, inputStyle } from '../styles';

interface TemperatureStatus {
  temperatureF: number | null;
  stationId: string | null;
  thresholdF: number;
  surgeActive: boolean;
  gapDetected: boolean;
  lastChecked: string | null;
}

function SurgeTab() {
  const intl = useIntl();
  const [surges, setSurges] = useState<{ id: string; status: string; reason: string; activatedAt: string; deactivatedAt: string | null }[]>([]);
  const [tempStatus, setTempStatus] = useState<TemperatureStatus | null>(null);
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

  const loadTempStatus = useCallback(async () => {
    try {
      const data = await api.get<TemperatureStatus>('/api/v1/monitoring/temperature');
      setTempStatus(data);
    } catch { /* monitor may not have run yet */ }
  }, []);

  useEffect(() => { fetchSurges(); loadTempStatus(); }, [fetchSurges, loadTempStatus]);

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

  const sectionStyle: React.CSSProperties = {
    background: color.bg, borderRadius: 12, padding: 20,
    boxShadow: '0 1px 3px rgba(0,0,0,0.08)', marginBottom: 16,
  };

  return (
    <div>
      {error && <ErrorBox message={error} />}

      {/* Temperature Status Banner (moved from Observability tab) */}
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

      {/* Surge Activation Threshold (moved from Observability tab) */}
      <SurgeTemperatureSettings />

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
