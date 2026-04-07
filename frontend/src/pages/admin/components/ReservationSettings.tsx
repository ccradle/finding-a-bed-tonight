import { useState, useEffect, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

import { AuthContext } from '../../../auth/AuthContext';

export function ReservationSettings() {
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
      const current = await api.get<Record<string, unknown>>(`/api/v1/tenants/${tenantId}/config`);
      await api.put(`/api/v1/tenants/${tenantId}/config`, { ...current, hold_duration_minutes: holdDuration });
      setMessage({ type: 'success', text: intl.formatMessage({ id: 'admin.holdDuration.saved' }) });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setMessage({ type: 'error', text: apiErr.message || intl.formatMessage({ id: 'admin.holdDuration.saveError' }) });
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
