import { useState, useEffect, useContext } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../../../services/api';
import { color } from '../../../theme/colors';
import { text, weight } from '../../../theme/typography';

import { AuthContext } from '../../../auth/AuthContext';

/**
 * dv-policy-tenant-flag — admin panel section letting a COC_ADMIN flip
 * the tenant-wide {@code dv_policy_enabled} JSONB flag.
 *
 * <p>Mirrors the {@link ReservationSettings} pattern but with toggle
 * semantics + extra-confirm modal pre-flip (warroom round 1, Casey/Keisha).
 * Distinct enable / disable modal copy (warroom round 1, Simone) and
 * inventory-link in the disable-rejection error UI (warroom round 1,
 * Demetrius/Devon).
 *
 * <p>No-optimistic-update pattern (Simone): the toggle does NOT flip until
 * the PATCH response is received, so a disable rejection (caused by
 * existing active DV shelters) doesn't produce a flicker / double-state
 * confusion. Spinner shown during request.
 *
 * <p>Endpoint requires both {@code COC_ADMIN} role and {@code dvAccess=true}
 * on the JWT (design D10) — RLS coupling means a non-DV-access caller's
 * disable-path count would return 0 and wrongly succeed; the controller
 * guards against this with a 403, surfaced here as a generic "no
 * permission" message.
 *
 * <p>Source copy: see {@code openspec/changes/dv-policy-tenant-flag/copy-draft-en.md}
 * (synthetic Casey+Keisha review applied; real-reviewer sign-off still
 * required at task §13.3).
 */
export function DvPolicySettings() {
  const intl = useIntl();
  const { user } = useContext(AuthContext);
  const tenantId = user?.tenantId;

  const [enabled, setEnabled] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [showConfirm, setShowConfirm] = useState<'enable' | 'disable' | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<{ message: string; remainingDvShelters?: number } | null>(null);

  useEffect(() => {
    if (!tenantId) return;
    (async () => {
      try {
        const config = await api.get<Record<string, unknown>>(`/api/v1/tenants/${tenantId}/config`);
        if (config && typeof config === 'object' && 'dv_policy_enabled' in config) {
          setEnabled(Boolean(config.dv_policy_enabled));
        }
        setLoaded(true);
      } catch {
        setLoadFailed(true);
        setLoaded(true);
      }
    })();
  }, [tenantId]);

  const requestFlip = (newValue: boolean) => {
    setError(null);
    setShowConfirm(newValue ? 'enable' : 'disable');
  };

  const cancelFlip = () => {
    setShowConfirm(null);
  };

  const confirmFlip = async () => {
    if (!tenantId || !showConfirm) return;
    const newValue = showConfirm === 'enable';
    setSaving(true);
    setError(null);
    try {
      await api.patch(`/api/v1/admin/tenants/${tenantId}/dv-policy`, {
        dvPolicyEnabled: newValue,
      });
      // Only flip after the server confirms (no-optimistic-update).
      setEnabled(newValue);
      setShowConfirm(null);
    } catch (err) {
      // Disable-rejection: backend returns 400 with context.errorCode
      // tenant.dvPolicy.cannotDisableWhileDvSheltersExist + count.
      // Surface count + inventory link via the error state.
      let message = intl.formatMessage({ id: 'admin.dvPolicy.saveError' });
      let remaining: number | undefined;
      if (err instanceof ApiError) {
        const errorCode = err.context?.errorCode as string | undefined;
        const remainingRaw = err.context?.remaining_dv_shelter_count;
        if (errorCode === 'tenant.dvPolicy.cannotDisableWhileDvSheltersExist'
            && typeof remainingRaw === 'number') {
          remaining = remainingRaw;
          message = intl.formatMessage(
            { id: 'admin.dvPolicy.disableRejectedWithCount' },
            { count: remaining },
          );
        } else if (err.message) {
          message = err.message;
        }
      }
      setError({ message, remainingDvShelters: remaining });
      setShowConfirm(null);
    } finally {
      setSaving(false);
    }
  };

  if (!loaded) return null;

  const intent = showConfirm; // 'enable' | 'disable' | null
  const modalTitleId = intent === 'enable'
    ? 'admin.dvPolicy.modal.enable.title'
    : 'admin.dvPolicy.modal.disable.title';
  const modalBodyId = intent === 'enable'
    ? 'admin.dvPolicy.modal.enable.body'
    : 'admin.dvPolicy.modal.disable.body';
  const modalConfirmId = intent === 'enable'
    ? 'admin.dvPolicy.modal.enable.confirm'
    : 'admin.dvPolicy.modal.disable.confirm';

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
      data-testid="dv-policy-settings"
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ flex: '1 1 320px', minWidth: 0 }}>
          <h3 style={{ margin: 0, fontSize: text.base, fontWeight: weight.bold, color: color.text }}>
            <FormattedMessage id="admin.dvPolicy.heading" />
          </h3>
          <p style={{ fontSize: text.sm, color: color.textTertiary, margin: '4px 0 0' }}>
            {enabled ? (
              <FormattedMessage id="admin.dvPolicy.descriptionEnabled" />
            ) : (
              <FormattedMessage id="admin.dvPolicy.descriptionDisabled" />
            )}
          </p>
        </div>
        <button
          type="button"
          onClick={() => requestFlip(!enabled)}
          disabled={saving || loadFailed}
          aria-pressed={enabled}
          data-testid="dv-policy-toggle"
          style={{
            padding: '8px 16px',
            backgroundColor: (saving || loadFailed)
              ? color.borderMedium
              : (enabled ? color.success : color.borderMedium),
            color: color.textInverse,
            border: 'none',
            borderRadius: 8,
            fontSize: text.sm,
            fontWeight: weight.bold,
            cursor: (saving || loadFailed) ? 'not-allowed' : 'pointer',
            minHeight: 44,
          }}
        >
          {enabled
            ? <FormattedMessage id="admin.dvPolicy.toggleEnabled" />
            : <FormattedMessage id="admin.dvPolicy.toggleDisabled" />}
        </button>
      </div>

      {loadFailed && (
        <div
          role="alert"
          data-testid="dv-policy-load-failed"
          style={{
            marginTop: 12, padding: '10px 14px', borderRadius: 8,
            backgroundColor: color.errorBg, color: color.error,
            border: `1px solid ${color.errorBorder}`,
            fontSize: text.sm, fontWeight: weight.semibold,
          }}
        >
          <FormattedMessage id="admin.dvPolicy.loadFailed" />
        </div>
      )}

      {error && (
        <div
          role="alert"
          data-testid="dv-policy-error"
          style={{
            marginTop: 12, padding: '10px 14px', borderRadius: 8,
            backgroundColor: color.errorBg, color: color.error,
            border: `1px solid ${color.errorBorder}`,
            fontSize: text.sm, fontWeight: weight.semibold,
          }}
        >
          <div>{error.message}</div>
          {error.remainingDvShelters !== undefined && error.remainingDvShelters > 0 && (
            // Inventory link — Demetrius/Devon round 1. Routes to admin
            // Shelters tab filtered to active DV shelters so the operator
            // can identify what blocks the disable.
            <a
              href="?tab=shelters&dvShelter=true&active=true"
              data-testid="dv-policy-shelter-inventory-link"
              style={{
                display: 'inline-block', marginTop: 6,
                color: color.error, fontSize: text.sm,
                textDecoration: 'underline',
              }}
            >
              <FormattedMessage id="admin.dvPolicy.inventoryLinkLabel" />
            </a>
          )}
        </div>
      )}

      {/* Extra-confirm modal — distinct copy per direction (Simone round 1) */}
      {intent && (
        <div
          role="dialog"
          aria-modal="true"
          aria-labelledby="dv-policy-modal-title"
          data-testid="dv-policy-confirm-modal"
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
              id="dv-policy-modal-title"
              style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold, color: color.text }}
            >
              <FormattedMessage id={modalTitleId} />
            </h2>
            <div style={{ marginTop: 12, fontSize: text.base, color: color.text, lineHeight: 1.5 }}>
              <FormattedMessage id={modalBodyId} />
            </div>
            <div style={{ marginTop: 20, display: 'flex', gap: 12, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
              <button
                type="button"
                onClick={cancelFlip}
                disabled={saving}
                data-testid="dv-policy-cancel-button"
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
                onClick={confirmFlip}
                disabled={saving}
                data-testid="dv-policy-confirm-button"
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
                {saving ? '...' : <FormattedMessage id={modalConfirmId} />}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
