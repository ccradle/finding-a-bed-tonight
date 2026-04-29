import { useState, useEffect, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

import { AuthContext } from '../../../auth/AuthContext';

/**
 * transitional-reentry-support slice 4 §12 — admin panel section that lets a
 * COC_ADMIN read + update the tenant-wide bed-hold duration (minutes).
 *
 * <p>§12.1 wire-up: switched from {@code PUT /api/v1/tenants/{id}/config}
 * (full-blob update of the tenant.config JSONB) to the dedicated
 * {@code PATCH /api/v1/admin/tenants/{id}/hold-duration} endpoint. The
 * dedicated endpoint:
 * <ul>
 *   <li>Bean-validates the 30–480 range at the boundary, rejecting bad
 *       input with a 400 + clear detail before the service layer runs.</li>
 *   <li>Emits a {@code TENANT_CONFIG_UPDATED} audit row with old + new
 *       values (slice 2D warroom B1).</li>
 *   <li>Enforces tenant scoping: COC_ADMIN of tenant A cannot PATCH
 *       tenant B's config even with B's UUID (slice 2D verify-round-2 C1).</li>
 * </ul>
 *
 * <p>The GET path still reads {@code tenant.config} as JSONB and pulls the
 * snake_case key {@code hold_duration_minutes} — that JSONB convention is
 * established by the V76/V77 seed migrations and consumed by
 * {@code ReservationService.getHoldDurationMinutes}. The PATCH body uses
 * camelCase {@code holdDurationMinutes} per Java/REST convention; the split
 * is documented on the backend {@code HoldDurationRequest} record.
 *
 * <p>§12.2 range: HTML5 {@code min="30" max="480"} clamps the spinner +
 * onChange clamps typed values. JS guard in {@code handleSave} catches
 * out-of-range submits with the localized {@code admin.holdDuration.rangeError}
 * before the network call.
 *
 * <p>§12.3 confirmation: the success toast renders the saved minutes value
 * via {@code admin.holdDuration.savedWithValue} (e.g., "Hold duration
 * saved: 180 minutes"). The toast auto-dismisses after 4s; no page reload.
 */
const HOLD_DURATION_MIN = 30;
const HOLD_DURATION_MAX = 480;
const HOLD_DURATION_DEFAULT = 90;
const TOAST_DISMISS_MS = 4000;

export function ReservationSettings() {
  const intl = useIntl();
  const { user } = useContext(AuthContext);
  const tenantId = user?.tenantId;
  const [holdDuration, setHoldDuration] = useState(HOLD_DURATION_DEFAULT);
  const [loaded, setLoaded] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    if (!tenantId) return;
    (async () => {
      try {
        const config = await api.get<Record<string, unknown>>(`/api/v1/tenants/${tenantId}/config`);
        if (config && typeof config === 'object' && 'hold_duration_minutes' in config) {
          setHoldDuration(Number(config.hold_duration_minutes) || HOLD_DURATION_DEFAULT);
        }
        setLoaded(true);
      } catch { setLoaded(true); }
    })();
  }, [tenantId]);

  // Auto-dismiss the success/error message so the panel doesn't accumulate
  // stale state if the operator saves repeatedly. Errors stay until the
  // next save attempt for visibility (a transient toast would hide the
  // reason).
  useEffect(() => {
    if (message?.type !== 'success') return undefined;
    const t = setTimeout(() => setMessage(null), TOAST_DISMISS_MS);
    return () => clearTimeout(t);
  }, [message]);

  const handleSave = async () => {
    if (!tenantId) return;
    // §12.2 — range guard before network call. The HTML5 min/max already
    // clamps the spinner UI, but a paste of "9999" or a manual edit can
    // bypass that. JS guard surfaces the localized message immediately.
    if (holdDuration < HOLD_DURATION_MIN || holdDuration > HOLD_DURATION_MAX) {
      setMessage({ type: 'error', text: intl.formatMessage({ id: 'admin.holdDuration.rangeError' }) });
      return;
    }
    setSaving(true); setMessage(null);
    try {
      // §12.1 — dedicated PATCH endpoint emits TENANT_CONFIG_UPDATED audit
      // and Bean-validates the 30–480 range at the boundary. Body uses
      // camelCase per HoldDurationRequest record; the JSONB-stored config
      // is snake_case (read path above) — split is intentional.
      await api.patch(`/api/v1/admin/tenants/${tenantId}/hold-duration`, {
        holdDurationMinutes: holdDuration,
      });
      // §12.3 — confirmation includes the new duration so the operator
      // sees what was saved (vs. a generic "Saved" that doesn't confirm
      // the value actually landed).
      setMessage({
        type: 'success',
        text: intl.formatMessage(
          { id: 'admin.holdDuration.savedWithValue' },
          { minutes: holdDuration },
        ),
      });
    } catch (err: unknown) {
      // Server-side detail (Bean Validation 400, tenant-scoping 403, etc.)
      // surfaces via context.detail when present — preserves the boundary
      // error without overwriting it with a generic localized fallback.
      const apiErr = err as { context?: { detail?: string }; message?: string };
      setMessage({
        type: 'error',
        text: apiErr.context?.detail
          || apiErr.message
          || intl.formatMessage({ id: 'admin.holdDuration.saveError' }),
      });
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
          min={HOLD_DURATION_MIN}
          max={HOLD_DURATION_MAX}
          step={5}
          value={holdDuration}
          onChange={e => {
            const parsed = parseInt(e.target.value);
            if (Number.isNaN(parsed)) {
              setHoldDuration(HOLD_DURATION_DEFAULT);
              return;
            }
            // Clamp on input so a typed "1000" can't render as out-of-range
            // before the user blurs; range error surfaces from handleSave
            // if the value somehow remains out of bounds.
            setHoldDuration(Math.max(HOLD_DURATION_MIN, Math.min(HOLD_DURATION_MAX, parsed)));
          }}
          aria-label={intl.formatMessage({ id: 'admin.holdDurationMinutes', defaultMessage: 'Hold duration (minutes)' })}
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
          <span
            aria-live="polite"
            role={message.type === 'error' ? 'alert' : 'status'}
            data-testid={`hold-duration-${message.type}`}
            style={{ fontSize: text.sm, color: message.type === 'success' ? color.success : color.error, fontWeight: weight.semibold }}
          >
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
