import { useState, useEffect, useCallback } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../../../../theme/colors';
import { text, weight } from '../../../../theme/typography';
import { api } from '../../../../services/api';
import { primaryBtnStyle, inputStyle } from '../../styles';
import { ErrorBox, Spinner } from '../../components';

/**
 * Local-to-the-tab type. Mirrors backend `EscalationPolicyDto` and
 * `EscalationPolicyThresholdDto` field-for-field. Tab-specific types live
 * IN the tab file per archived admin-panel-extraction convention.
 */
interface EscalationPolicyThreshold {
  id: string;
  at: string;            // ISO-8601 duration, e.g. "PT1H", "PT2H30M"
  severity: string;      // "INFO" | "ACTION_REQUIRED" | "CRITICAL"
  recipients: string[];  // ["COORDINATOR", "COC_ADMIN", ...]
}

interface EscalationPolicy {
  id?: string;
  tenantId?: string | null;
  eventType: string;
  version: number;
  thresholds: EscalationPolicyThreshold[];
  createdAt?: string;
  createdBy?: string | null;
}

const VALID_SEVERITIES = ['INFO', 'ACTION_REQUIRED', 'CRITICAL'] as const;
const VALID_ROLES = ['COORDINATOR', 'COC_ADMIN', 'OUTREACH_WORKER', 'PLATFORM_ADMIN'] as const;
const ID_PATTERN = /^[a-z0-9_]{1,32}$/;
const ISO_DURATION_PATTERN = /^PT(?:\d+H)?(?:\d+M)?(?:\d+S)?$/;
const MAX_THRESHOLDS = 50;

interface ValidationError {
  index: number | null;
  field: string;
  messageId: string;
  values?: Record<string, string | number>;
}

function validateThresholds(thresholds: EscalationPolicyThreshold[]): ValidationError[] {
  const errors: ValidationError[] = [];
  if (thresholds.length === 0) {
    errors.push({ index: null, field: 'thresholds', messageId: 'dvEscalations.policy.error.empty' });
    return errors;
  }
  if (thresholds.length > MAX_THRESHOLDS) {
    errors.push({ index: null, field: 'thresholds', messageId: 'dvEscalations.policy.error.tooMany' });
  }

  const seenIds = new Set<string>();
  let previousMs: number | null = null;

  thresholds.forEach((t, i) => {
    if (!t.id || !ID_PATTERN.test(t.id)) {
      errors.push({ index: i, field: 'id', messageId: 'dvEscalations.policy.error.idFormat' });
    } else if (seenIds.has(t.id)) {
      errors.push({ index: i, field: 'id', messageId: 'dvEscalations.policy.error.idDuplicate' });
    } else {
      seenIds.add(t.id);
    }

    if (!t.at || !ISO_DURATION_PATTERN.test(t.at)) {
      errors.push({ index: i, field: 'at', messageId: 'dvEscalations.policy.error.duration' });
    } else {
      const ms = isoToMs(t.at);
      if (previousMs !== null && ms <= previousMs) {
        errors.push({
          index: i, field: 'at',
          messageId: 'dvEscalations.policy.error.monotonic',
          values: { index: i + 1 },
        });
      }
      previousMs = ms;
    }

    if (!VALID_SEVERITIES.includes(t.severity as typeof VALID_SEVERITIES[number])) {
      errors.push({ index: i, field: 'severity', messageId: 'dvEscalations.policy.error.severity' });
    }

    if (!t.recipients || t.recipients.length === 0) {
      errors.push({ index: i, field: 'recipients', messageId: 'dvEscalations.policy.error.recipientsEmpty' });
    } else {
      for (const r of t.recipients) {
        if (!VALID_ROLES.includes(r as typeof VALID_ROLES[number])) {
          errors.push({ index: i, field: 'recipients', messageId: 'dvEscalations.policy.error.role' });
          break;
        }
      }
    }
  });

  return errors;
}

/** Quick ISO-8601 duration parser sufficient for monotonic comparison. */
function isoToMs(iso: string): number {
  const m = iso.match(/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/);
  if (!m) return 0;
  const hours = parseInt(m[1] || '0', 10);
  const mins = parseInt(m[2] || '0', 10);
  const secs = parseInt(m[3] || '0', 10);
  return ((hours * 60 + mins) * 60 + secs) * 1000;
}

interface EscalationPolicyEditorProps {
  isMobile: boolean;
}

/**
 * T-38 — Per-tenant escalation policy editor (desktop-only).
 *
 * <p><b>Mobile:</b> renders a read-only message and form fields disabled.
 * The Save button is NOT in the DOM at all on mobile (preventing accidental
 * tap-to-submit on a form full of disabled values per the spec contract).</p>
 *
 * <p><b>Validation:</b> client-side rules MIRROR the server-side
 * `EscalationPolicyService.validateThresholds` so the user gets immediate
 * feedback before submit. The server still validates on PATCH — this is
 * UX, not security.</p>
 */
export function EscalationPolicyEditor({ isMobile }: EscalationPolicyEditorProps) {
  const intl = useIntl();
  const [policy, setPolicy] = useState<EscalationPolicy | null>(null);
  const [thresholds, setThresholds] = useState<EscalationPolicyThreshold[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [savedFlash, setSavedFlash] = useState(false);

  const fetchPolicy = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.get<EscalationPolicy>('/api/v1/admin/escalation-policy/dv-referral');
      setPolicy(data);
      setThresholds(data.thresholds || []);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.loadFailed' }));
    } finally {
      setLoading(false);
    }
  }, [intl]);

  useEffect(() => { fetchPolicy(); }, [fetchPolicy]);

  // Auto-clear the success flash after 5 seconds.
  useEffect(() => {
    if (!savedFlash) return;
    const timer = setTimeout(() => setSavedFlash(false), 5000);
    return () => clearTimeout(timer);
  }, [savedFlash]);

  const validationErrors = validateThresholds(thresholds);
  const hasErrors = validationErrors.length > 0;

  const updateThreshold = (i: number, updates: Partial<EscalationPolicyThreshold>) => {
    setThresholds((prev) => prev.map((t, idx) => (idx === i ? { ...t, ...updates } : t)));
  };

  const addThreshold = () => {
    if (thresholds.length >= MAX_THRESHOLDS) return;
    setThresholds((prev) => [
      ...prev,
      { id: '', at: 'PT1H', severity: 'INFO', recipients: ['COORDINATOR'] },
    ]);
  };

  const removeThreshold = (i: number) => {
    setThresholds((prev) => prev.filter((_, idx) => idx !== i));
  };

  const toggleRecipient = (i: number, role: string) => {
    setThresholds((prev) => prev.map((t, idx) => {
      if (idx !== i) return t;
      const has = t.recipients.includes(role);
      return {
        ...t,
        recipients: has ? t.recipients.filter((r) => r !== role) : [...t.recipients, role],
      };
    }));
  };

  const handleSave = async () => {
    if (hasErrors) return;
    setSaving(true);
    setError(null);
    try {
      const updated = await api.patch<EscalationPolicy>(
        '/api/v1/admin/escalation-policy/dv-referral',
        { thresholds },
      );
      setPolicy(updated);
      setThresholds(updated.thresholds || []);
      setSavedFlash(true);
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
    } finally {
      setSaving(false);
    }
  };

  const errorsForIndex = (i: number) =>
    validationErrors.filter((e) => e.index === i);

  if (loading) return <Spinner />;
  if (error && !policy) return <ErrorBox message={error} />;

  // Mobile read-only message — Save button NOT in the DOM at all.
  if (isMobile) {
    return (
      <div
        data-testid="dv-escalation-policy-mobile-readonly"
        style={{
          padding: 20,
          backgroundColor: color.bgSecondary,
          border: `2px dashed ${color.border}`,
          borderRadius: 12,
          textAlign: 'center',
          color: color.textSecondary,
          fontSize: text.base,
          lineHeight: 1.5,
        }}
      >
        <FormattedMessage id="dvEscalations.policy.mobileReadOnly" />
      </div>
    );
  }

  return (
    <div data-testid="dv-escalation-policy-editor">
      <div style={{ marginBottom: 16 }}>
        <h3 style={{
          margin: 0, marginBottom: 4,
          fontSize: text.lg, fontWeight: weight.bold, color: color.text,
        }}>
          <FormattedMessage id="dvEscalations.policy.title" />
        </h3>
        <p style={{
          margin: 0,
          fontSize: text.sm,
          color: color.textSecondary,
        }}>
          <FormattedMessage id="dvEscalations.policy.subtitle" />
        </p>
        {policy && (
          <p style={{ margin: '4px 0 0', fontSize: text.xs, color: color.textMuted }}>
            v{policy.version}
          </p>
        )}
      </div>

      {error && <ErrorBox message={error} />}
      {savedFlash && (
        <div
          role="status"
          data-testid="dv-escalation-policy-saved"
          style={{
            padding: 10,
            marginBottom: 12,
            backgroundColor: color.successBg,
            color: color.text,
            border: `1px solid ${color.successBorder}`,
            borderRadius: 6,
            fontSize: text.sm,
          }}
        >
          <FormattedMessage id="dvEscalations.policy.saved" />
        </div>
      )}

      {thresholds.map((t, i) => {
        const tErrors = errorsForIndex(i);
        return (
          <div
            key={i}
            data-testid={`dv-escalation-policy-threshold-${i}`}
            style={{
              padding: 16,
              marginBottom: 12,
              border: `2px solid ${tErrors.length > 0 ? color.errorBorder : color.border}`,
              borderRadius: 10,
              backgroundColor: color.bg,
            }}
          >
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <label>
                <span style={{
                  display: 'block', marginBottom: 4,
                  fontSize: text.xs, fontWeight: weight.semibold, color: color.textSecondary,
                }}>
                  <FormattedMessage id="dvEscalations.policy.field.id" />
                </span>
                <input
                  type="text"
                  value={t.id}
                  onChange={(e) => updateThreshold(i, { id: e.target.value })}
                  data-testid={`dv-escalation-policy-id-${i}`}
                  style={inputStyle}
                />
              </label>
              <label>
                <span style={{
                  display: 'block', marginBottom: 4,
                  fontSize: text.xs, fontWeight: weight.semibold, color: color.textSecondary,
                }}>
                  <FormattedMessage id="dvEscalations.policy.field.duration" />
                </span>
                <input
                  type="text"
                  value={t.at}
                  onChange={(e) => updateThreshold(i, { at: e.target.value })}
                  data-testid={`dv-escalation-policy-at-${i}`}
                  style={inputStyle}
                />
              </label>
            </div>

            <label style={{ display: 'block', marginTop: 12 }}>
              <span style={{
                display: 'block', marginBottom: 4,
                fontSize: text.xs, fontWeight: weight.semibold, color: color.textSecondary,
              }}>
                <FormattedMessage id="dvEscalations.policy.field.severity" />
              </span>
              <select
                value={t.severity}
                onChange={(e) => updateThreshold(i, { severity: e.target.value })}
                data-testid={`dv-escalation-policy-severity-${i}`}
                style={inputStyle}
              >
                {VALID_SEVERITIES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </label>

            <fieldset style={{ border: 'none', padding: 0, marginTop: 12, marginBottom: 0 }}>
              <legend style={{
                fontSize: text.xs, fontWeight: weight.semibold,
                color: color.textSecondary, marginBottom: 4,
              }}>
                <FormattedMessage id="dvEscalations.policy.field.recipients" />
              </legend>
              <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                {VALID_ROLES.map((role) => (
                  <label key={role} style={{
                    display: 'flex', alignItems: 'center', gap: 6,
                    cursor: 'pointer', minHeight: 32,
                  }}>
                    <input
                      type="checkbox"
                      checked={t.recipients.includes(role)}
                      onChange={() => toggleRecipient(i, role)}
                      data-testid={`dv-escalation-policy-recipient-${i}-${role}`}
                      style={{ width: 18, height: 18 }}
                    />
                    <span style={{ fontSize: text.sm, color: color.text }}>{role}</span>
                  </label>
                ))}
              </div>
            </fieldset>

            {tErrors.length > 0 && (
              <ul
                role="alert"
                data-testid={`dv-escalation-policy-errors-${i}`}
                style={{
                  margin: '8px 0 0', padding: '8px 16px',
                  backgroundColor: color.errorBg,
                  borderRadius: 6,
                  listStyle: 'disc',
                  fontSize: text.xs,
                  color: color.errorMid,
                }}
              >
                {tErrors.map((e, idx) => (
                  <li key={idx}>
                    <FormattedMessage id={e.messageId} values={e.values} />
                  </li>
                ))}
              </ul>
            )}

            <button
              onClick={() => removeThreshold(i)}
              data-testid={`dv-escalation-policy-remove-${i}`}
              style={{
                marginTop: 12,
                padding: '8px 14px',
                background: 'none',
                border: `1px solid ${color.errorBorder}`,
                borderRadius: 6,
                fontSize: text.xs,
                fontWeight: weight.semibold,
                color: color.errorMid,
                cursor: 'pointer',
                minHeight: 32,
              }}
            >
              <FormattedMessage id="dvEscalations.policy.remove" />
            </button>
          </div>
        );
      })}

      {/* Top-level errors (not tied to a specific threshold) */}
      {validationErrors.filter((e) => e.index === null).map((e, i) => (
        <div
          key={i}
          role="alert"
          style={{
            padding: 10, marginBottom: 12,
            backgroundColor: color.errorBg, color: color.errorMid,
            border: `1px solid ${color.errorBorder}`, borderRadius: 6,
            fontSize: text.sm,
          }}
        >
          <FormattedMessage id={e.messageId} />
        </div>
      ))}

      <div style={{ display: 'flex', gap: 10, marginTop: 16 }}>
        <button
          onClick={addThreshold}
          disabled={thresholds.length >= MAX_THRESHOLDS}
          data-testid="dv-escalation-policy-add"
          style={{
            padding: '12px 20px',
            background: 'none',
            border: `2px solid ${color.border}`,
            borderRadius: 10,
            fontSize: text.base, fontWeight: weight.semibold,
            color: color.text, cursor: 'pointer',
            minHeight: 44,
            opacity: thresholds.length >= MAX_THRESHOLDS ? 0.5 : 1,
          }}
        >
          <FormattedMessage id="dvEscalations.policy.add" />
        </button>
        <button
          onClick={handleSave}
          disabled={saving || hasErrors}
          data-testid="dv-escalation-policy-save"
          style={{
            ...primaryBtnStyle,
            opacity: saving || hasErrors ? 0.5 : 1,
            cursor: saving || hasErrors ? 'not-allowed' : 'pointer',
          }}
        >
          <FormattedMessage id="dvEscalations.policy.save" />
        </button>
      </div>
    </div>
  );
}
