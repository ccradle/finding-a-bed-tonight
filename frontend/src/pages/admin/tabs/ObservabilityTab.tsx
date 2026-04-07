import { useState, useEffect, useCallback, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { AuthContext } from '../../../auth/AuthContext';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';


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
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setMessage({ type: 'error', text: apiErr.message || intl.formatMessage({ id: 'admin.observability.saveError' }) });
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


export default ObservabilityTab;
