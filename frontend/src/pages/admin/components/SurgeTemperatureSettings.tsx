import { useState, useEffect, useContext, useRef } from 'react';
import { FormattedMessage, useIntl, type IntlShape } from 'react-intl';
import { api, ApiError } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';
import { AuthContext } from '../../../auth/AuthContext';

/**
 * Pure helper extracted for Vitest coverage (codebase convention: no
 * RTL / jsdom in this tree, so component behavior is Playwright-tested
 * but business logic is unit-tested via extracted predicates — see
 * {@code DvPolicySettings.test.ts} for the same pattern).
 *
 * Inspects an ApiError thrown by the surge-threshold PUT and returns
 * either:
 * - {@code out-of-range} — backend rejected because the threshold was
 *   outside the allowed [-50, 150] band, missing, or non-numeric.
 *   Caller renders a localized "must be between -50 and 150" message.
 * - {@code generic} — any other ApiError or non-ApiError. Caller renders
 *   a generic "save failed" message with optional `err.message` fallback.
 */
export type TemperatureErrorParse =
  | { kind: 'out-of-range'; message: string }
  | { kind: 'generic'; message?: string };

export function parseTemperatureError(err: unknown, intl: IntlShape): TemperatureErrorParse {
  if (!(err instanceof ApiError)) {
    return { kind: 'generic' };
  }
  const errorCode = err.context?.errorCode as string | undefined;
  if (errorCode === 'tenant.surgeThreshold.outOfRange') {
    return {
      kind: 'out-of-range',
      message: intl.formatMessage({ id: 'admin.observability.thresholdError' }),
    };
  }
  return { kind: 'generic', message: err.message };
}

/**
 * surge-temperature-threshold — admin-panel section letting a COC_ADMIN
 * configure the temperature threshold below which the platform recommends
 * activating surge mode (per-tenant geographic concern).
 *
 * <p>Splits out the only per-tenant knob remaining from the old
 * observability tab (platform-observability-split 2026-05-02). Mirrors the
 * {@link DvPolicySettings} pattern:
 * <ul>
 *   <li>Pure-helper error parsing ({@link parseTemperatureError}) for Vitest.</li>
 *   <li>No-optimistic-update on save — input flips to the persisted value
 *       only after the PUT response arrives.</li>
 *   <li>Modal-with-Escape + auto-focus-on-cancel-button for keyboard users
 *       (warroom round 3 H1+H2 lessons).</li>
 *   <li>data-testids on every interactive element matching the spec §6.7
 *       contract (warroom round 4 fix — original implementation had renamed
 *       data-testids that didn't match docs).</li>
 * </ul>
 */
export function SurgeTemperatureSettings() {
  const intl = useIntl();
  const { user } = useContext(AuthContext);
  const tenantId = user?.tenantId;

  const [persistedThreshold, setPersistedThreshold] = useState<number>(32);
  const [draftThreshold, setDraftThreshold] = useState<number>(32);
  const [loaded, setLoaded] = useState(false);
  const [showConfirm, setShowConfirm] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedToast, setSavedToast] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Modal focus management (warroom round 3 H2). Auto-focus the cancel
  // button on open so keyboard users land safely inside the modal.
  const cancelButtonRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (showConfirm) cancelButtonRef.current?.focus();
  }, [showConfirm]);

  useEffect(() => {
    if (!tenantId) return;
    (async () => {
      try {
        const data = await api.get<{ temperature_threshold_f: number }>(
          `/api/v1/tenants/${tenantId}/surge-threshold`,
        );
        setPersistedThreshold(data.temperature_threshold_f);
        setDraftThreshold(data.temperature_threshold_f);
      } catch {
        // Default already applied via useState initial values.
      } finally {
        setLoaded(true);
      }
    })();
  }, [tenantId]);

  const requestSave = () => {
    setError(null);
    setSavedToast(false);
    if (Number.isNaN(draftThreshold)) {
      setError(intl.formatMessage({ id: 'admin.observability.thresholdError' }));
      return;
    }
    if (draftThreshold === persistedThreshold) {
      // No-op — surface a "saved" toast without making a network call so
      // operators don't see "saving..." forever on a no-change confirm.
      setSavedToast(true);
      return;
    }
    setShowConfirm(true);
  };

  const cancelSave = () => {
    setShowConfirm(false);
    // Reset draft to the persisted value so the next confirm is clean.
    setDraftThreshold(persistedThreshold);
  };

  const confirmSave = async () => {
    if (!tenantId) return;
    setSaving(true);
    setError(null);
    setSavedToast(false);
    try {
      await api.put(`/api/v1/tenants/${tenantId}/surge-threshold`, {
        temperature_threshold_f: draftThreshold,
      });
      // Only flip the persisted state after the server confirms (no-optimistic).
      setPersistedThreshold(draftThreshold);
      setShowConfirm(false);
      setSavedToast(true);
    } catch (err) {
      const parsed = parseTemperatureError(err, intl);
      setError(
        parsed.message ?? intl.formatMessage({ id: 'admin.observability.saveError' }),
      );
      setShowConfirm(false);
    } finally {
      setSaving(false);
    }
  };

  if (!loaded) return null;

  return (
    <div
      style={{
        background: color.bg,
        borderRadius: 12,
        padding: 16,
        marginBottom: 16,
        border: `1px solid ${color.border}`,
        boxShadow: '0 1px 3px rgba(0,0,0,0.06)',
      }}
      data-testid="surge-temperature-settings"
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 300px' }}>
          <h3 style={{ margin: 0, fontSize: text.base, fontWeight: weight.bold, color: color.text }}>
            <FormattedMessage id="admin.observability.tempThreshold" />
          </h3>
          <p style={{ fontSize: text.sm, color: color.textTertiary, margin: '4px 0 0' }}>
            <FormattedMessage id="admin.observability.thresholdDescription" />
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ position: 'relative' }}>
            <input
              type="number"
              value={Number.isNaN(draftThreshold) ? '' : draftThreshold}
              onChange={(e) => {
                const v = e.target.value;
                setDraftThreshold(v === '' ? Number.NaN : parseFloat(v));
              }}
              disabled={saving}
              data-testid="temperature-threshold-input"
              aria-label={intl.formatMessage({ id: 'admin.observability.tempThreshold' })}
              style={{
                padding: '8px 32px 8px 12px',
                border: `1px solid ${color.borderMedium}`,
                borderRadius: 8,
                fontSize: text.base,
                width: 100,
                textAlign: 'right',
              }}
            />
            <span
              aria-hidden="true"
              style={{
                position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)',
                fontSize: text.xs, color: color.textMuted, pointerEvents: 'none',
              }}
            >
              °F
            </span>
          </div>

          <button
            type="button"
            onClick={requestSave}
            disabled={saving}
            data-testid="temperature-save-button"
            style={{
              padding: '8px 20px',
              backgroundColor: saving ? color.borderMedium : color.primary,
              color: color.textInverse,
              border: 'none',
              borderRadius: 8,
              fontSize: text.sm,
              fontWeight: weight.bold,
              cursor: saving ? 'not-allowed' : 'pointer',
              minHeight: 44,
            }}
          >
            {saving ? '...' : <FormattedMessage id="admin.observability.save" />}
          </button>
        </div>
      </div>

      {error && (
        <div
          role="alert"
          data-testid="temperature-error"
          style={{
            marginTop: 12, padding: '10px 14px', borderRadius: 8,
            backgroundColor: color.errorBg, color: color.error,
            border: `1px solid ${color.errorBorder}`,
            fontSize: text.sm, fontWeight: weight.semibold,
          }}
        >
          {error}
        </div>
      )}

      {savedToast && !error && (
        <div
          role="status"
          data-testid="temperature-saved-toast"
          style={{
            marginTop: 12,
            fontSize: text.sm,
            fontWeight: weight.semibold,
            color: color.successBright,
          }}
        >
          <FormattedMessage id="admin.observability.saved" />
        </div>
      )}

      {/* Confirm modal — Escape closes (warroom round 3 H1), auto-focus on
          cancel button (warroom round 3 H2). Disabled while saving so an
          in-flight PUT can't be abandoned mid-flight. */}
      {showConfirm && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="surge-temperature-modal-title"
          data-testid="temperature-confirm-modal"
          onKeyDown={(e) => { if (e.key === 'Escape' && !saving) cancelSave(); }}
          style={{
            position: 'fixed', inset: 0,
            backgroundColor: 'rgba(0,0,0,0.5)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            zIndex: 1000,
          }}
        >
          <div
            style={{
              backgroundColor: color.bg,
              borderRadius: 12,
              padding: 24,
              maxWidth: 520,
              width: '90%',
              boxShadow: '0 8px 32px rgba(0,0,0,0.25)',
            }}
          >
            <h2
              id="surge-temperature-modal-title"
              style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold, color: color.text }}
            >
              <FormattedMessage id="admin.observability.confirmTitle" />
            </h2>
            <div style={{ marginTop: 12, fontSize: text.base, color: color.text, lineHeight: 1.5 }}>
              <FormattedMessage
                id="admin.observability.confirmBody"
                values={{ threshold: draftThreshold }}
              />
            </div>
            <div style={{ marginTop: 20, display: 'flex', gap: 12, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
              <button
                type="button"
                ref={cancelButtonRef}
                onClick={cancelSave}
                disabled={saving}
                data-testid="temperature-cancel-button"
                style={{
                  padding: '8px 16px',
                  backgroundColor: color.bg,
                  color: color.text,
                  border: `2px solid ${color.border}`,
                  borderRadius: 8,
                  fontSize: text.sm,
                  fontWeight: weight.semibold,
                  cursor: saving ? 'not-allowed' : 'pointer',
                  minHeight: 44,
                }}
              >
                <FormattedMessage id="common.cancel" />
              </button>
              <button
                type="button"
                onClick={confirmSave}
                disabled={saving}
                data-testid="temperature-confirm-button"
                style={{
                  padding: '8px 16px',
                  backgroundColor: saving ? color.borderMedium : color.primary,
                  color: color.textInverse,
                  border: 'none',
                  borderRadius: 8,
                  fontSize: text.sm,
                  fontWeight: weight.bold,
                  cursor: saving ? 'not-allowed' : 'pointer',
                  minHeight: 44,
                }}
              >
                {saving ? '...' : <FormattedMessage id="admin.observability.save" />}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
