import { useState, useEffect, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../../../../theme/colors';
import { text, weight } from '../../../../theme/typography';
import { api } from '../../../../services/api';
import { primaryBtnStyle, inputStyle } from '../../styles';

/**
 * Three target types for reassign — mirror backend
 * `ReassignReferralRequest.TargetType` enum field-for-field.
 */
type TargetType = 'COORDINATOR_GROUP' | 'COC_ADMIN_GROUP' | 'SPECIFIC_USER';

interface ReassignSubModalProps {
  referralId: string;
  shelterName: string;
  /** Called when the reassign POST returns 200 OK. The parent should refresh the queue. */
  onSuccess: () => void;
  /** Called when the user cancels (X button or Escape). */
  onCancel: () => void;
}

/**
 * T-37 — Three-target chooser sub-modal for reassigning a DV referral.
 *
 * <p><b>Disclosure pattern, NOT `role="menu"`</b> (D20, archived
 * sse-notifications D10): SPECIFIC_USER is gated behind a `<details>`
 * disclosure labeled "Advanced" per PagerDuty's documented warning that
 * single-user reassign breaks the escalation chain.</p>
 *
 * <p><b>PII discipline:</b> the reason text field shows a prominent warning
 * above it. The backend stores the reason verbatim in
 * `DV_REFERRAL_REASSIGNED.details.reason` but DELIBERATELY OMITS it from
 * the broadcast notification payload (Keisha Thompson war-room round 3 +
 * Marcus Webb persona note). The frontend modal is the only PII checkpoint —
 * the backend cannot enforce content.</p>
 */
export function ReassignSubModal({ referralId, shelterName, onSuccess, onCancel }: ReassignSubModalProps) {
  const intl = useIntl();
  const dialogRef = useRef<HTMLDivElement>(null);
  const initialFocusRef = useRef<HTMLInputElement>(null);

  const [targetType, setTargetType] = useState<TargetType>('COORDINATOR_GROUP');
  const [targetUserId, setTargetUserId] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Focus the first radio on open + Escape closes (modal accessibility, D20).
  useEffect(() => {
    initialFocusRef.current?.focus();
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onCancel();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onCancel]);

  const canSubmit =
    reason.trim().length > 0 &&
    !submitting &&
    (targetType !== 'SPECIFIC_USER' || targetUserId.trim().length > 0);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);

    const body: Record<string, string> = {
      targetType,
      reason: reason.trim(),
    };
    if (targetType === 'SPECIFIC_USER') {
      body.targetUserId = targetUserId.trim();
    }

    try {
      await api.post(`/api/v1/dv-referrals/${referralId}/reassign`, body);
      onSuccess();
    } catch (err: unknown) {
      const apiErr = err as { message?: string; status?: number };
      setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      {/* Backdrop — sub-modal sits on top of the detail modal */}
      <div
        onClick={onCancel}
        style={{
          position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.4)', zIndex: 1100,
        }}
      />
      {/* Dialog */}
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="reassign-submodal-title"
        data-testid="dv-escalation-reassign-modal"
        style={{
          position: 'fixed',
          top: '50%', left: '50%',
          transform: 'translate(-50%, -50%)',
          width: '90vw', maxWidth: 520, maxHeight: '85vh',
          backgroundColor: color.bg,
          borderRadius: 14,
          boxShadow: '0 20px 60px rgba(0,0,0,0.35)',
          zIndex: 1101,
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        <div style={{
          padding: '16px 20px',
          borderBottom: `1px solid ${color.border}`,
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <h3
            id="reassign-submodal-title"
            style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold, color: color.text }}
          >
            <FormattedMessage id="dvEscalations.reassign.title" />
          </h3>
          <button
            onClick={onCancel}
            aria-label={intl.formatMessage({ id: 'dvEscalations.modal.close' })}
            data-testid="dv-escalation-reassign-close"
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: text.xl, color: color.textMuted,
              minWidth: 44, minHeight: 44,
            }}
          >×</button>
        </div>

        <form onSubmit={handleSubmit} style={{ padding: 20, overflowY: 'auto' }}>
          <div style={{ fontSize: text.sm, color: color.textSecondary, marginBottom: 16 }}>
            {shelterName}
          </div>

          {/* Target type radios */}
          <fieldset style={{ border: 'none', padding: 0, margin: 0, marginBottom: 16 }}>
            <legend style={{
              fontSize: text.xs, fontWeight: weight.semibold,
              color: color.textSecondary, textTransform: 'uppercase',
              letterSpacing: '0.04em', marginBottom: 8,
            }}>
              <FormattedMessage id="dvEscalations.reassign.title" />
            </legend>

            <label style={{
              display: 'flex', alignItems: 'center', gap: 10,
              padding: 12, marginBottom: 8,
              border: `2px solid ${targetType === 'COORDINATOR_GROUP' ? color.primary : color.border}`,
              borderRadius: 8, cursor: 'pointer', backgroundColor: color.bg,
              minHeight: 44,
            }}>
              <input
                ref={initialFocusRef}
                type="radio"
                name="targetType"
                value="COORDINATOR_GROUP"
                checked={targetType === 'COORDINATOR_GROUP'}
                onChange={() => setTargetType('COORDINATOR_GROUP')}
                data-testid="dv-escalation-reassign-coordinator-group"
                style={{ width: 18, height: 18, cursor: 'pointer' }}
              />
              <span style={{ fontSize: text.base, color: color.text, fontWeight: weight.medium }}>
                <FormattedMessage id="dvEscalations.reassign.target.coordinatorGroup" />
              </span>
            </label>

            <label style={{
              display: 'flex', alignItems: 'center', gap: 10,
              padding: 12, marginBottom: 8,
              border: `2px solid ${targetType === 'COC_ADMIN_GROUP' ? color.primary : color.border}`,
              borderRadius: 8, cursor: 'pointer', backgroundColor: color.bg,
              minHeight: 44,
            }}>
              <input
                type="radio"
                name="targetType"
                value="COC_ADMIN_GROUP"
                checked={targetType === 'COC_ADMIN_GROUP'}
                onChange={() => setTargetType('COC_ADMIN_GROUP')}
                data-testid="dv-escalation-reassign-coc-admin-group"
                style={{ width: 18, height: 18, cursor: 'pointer' }}
              />
              <span style={{ fontSize: text.base, color: color.text, fontWeight: weight.medium }}>
                <FormattedMessage id="dvEscalations.reassign.target.cocAdminGroup" />
              </span>
            </label>
          </fieldset>

          {/* Advanced disclosure for SPECIFIC_USER — D20 disclosure pattern */}
          <details
            data-testid="dv-escalation-reassign-advanced"
            style={{
              marginBottom: 16,
              border: `1px solid ${color.borderLight}`,
              borderRadius: 8,
              padding: '8px 12px',
              backgroundColor: color.bgSecondary,
            }}
          >
            <summary style={{
              cursor: 'pointer',
              fontSize: text.sm,
              fontWeight: weight.semibold,
              color: color.textSecondary,
              minHeight: 28,
            }}>
              <FormattedMessage id="dvEscalations.reassign.advanced" />
            </summary>
            <div style={{ paddingTop: 12 }}>
              <div style={{
                padding: 10,
                marginBottom: 12,
                backgroundColor: color.warningBg,
                border: `1px solid ${color.warningMid}`,
                borderRadius: 6,
                fontSize: text.xs,
                color: color.text,
                lineHeight: 1.5,
              }}>
                <strong>⚠ </strong>
                <FormattedMessage id="dvEscalations.reassign.advancedWarning" />
              </div>

              <label style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: 10, marginBottom: 12,
                border: `2px solid ${targetType === 'SPECIFIC_USER' ? color.primary : color.border}`,
                borderRadius: 8, cursor: 'pointer', backgroundColor: color.bg,
                minHeight: 44,
              }}>
                <input
                  type="radio"
                  name="targetType"
                  value="SPECIFIC_USER"
                  checked={targetType === 'SPECIFIC_USER'}
                  onChange={() => setTargetType('SPECIFIC_USER')}
                  data-testid="dv-escalation-reassign-specific-user"
                  style={{ width: 18, height: 18, cursor: 'pointer' }}
                />
                <span style={{ fontSize: text.base, color: color.text, fontWeight: weight.medium }}>
                  <FormattedMessage id="dvEscalations.reassign.target.specificUser" />
                </span>
              </label>

              {targetType === 'SPECIFIC_USER' && (
                <label style={{ display: 'block', marginBottom: 8 }}>
                  <span style={{
                    display: 'block', marginBottom: 4,
                    fontSize: text.xs, fontWeight: weight.semibold, color: color.textSecondary,
                  }}>
                    <FormattedMessage id="dvEscalations.reassign.targetUserLabel" />
                  </span>
                  <input
                    type="text"
                    value={targetUserId}
                    onChange={(e) => setTargetUserId(e.target.value)}
                    placeholder={intl.formatMessage({ id: 'dvEscalations.reassign.targetUserPlaceholder' })}
                    data-testid="dv-escalation-reassign-target-user-id"
                    style={inputStyle}
                  />
                </label>
              )}
            </div>
          </details>

          {/* PII warning above reason field — Marcus Webb + Keisha Thompson */}
          <div style={{
            padding: 10,
            marginBottom: 8,
            backgroundColor: color.warningBg,
            border: `1px solid ${color.warningMid}`,
            borderRadius: 6,
            fontSize: text.xs,
            color: color.text,
            lineHeight: 1.5,
          }}>
            <strong>⚠ </strong>
            <FormattedMessage id="dvEscalations.modal.piiWarning" />
          </div>

          <label style={{ display: 'block', marginBottom: 16 }}>
            <span style={{
              display: 'block', marginBottom: 4,
              fontSize: text.xs, fontWeight: weight.semibold, color: color.textSecondary,
            }}>
              <FormattedMessage id="dvEscalations.reassign.reasonLabel" />
            </span>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              maxLength={500}
              rows={3}
              data-testid="dv-escalation-reassign-reason"
              style={{ ...inputStyle, fontFamily: 'inherit', resize: 'vertical' }}
            />
          </label>

          {error && (
            <div
              role="alert"
              style={{
                padding: 10, marginBottom: 12,
                backgroundColor: color.errorBg, color: color.errorMid,
                border: `1px solid ${color.errorBorder}`, borderRadius: 6,
                fontSize: text.sm,
              }}
            >
              {error}
            </div>
          )}

          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button
              type="button"
              onClick={onCancel}
              data-testid="dv-escalation-reassign-cancel"
              style={{
                padding: '12px 20px',
                background: 'none',
                border: `2px solid ${color.border}`,
                borderRadius: 10,
                fontSize: text.base, fontWeight: weight.semibold,
                color: color.text, cursor: 'pointer',
                minHeight: 44,
              }}
            >
              <FormattedMessage id="dvEscalations.modal.close" />
            </button>
            <button
              type="submit"
              disabled={!canSubmit}
              data-testid="dv-escalation-reassign-submit"
              style={{
                ...primaryBtnStyle,
                opacity: canSubmit ? 1 : 0.5,
                cursor: canSubmit ? 'pointer' : 'not-allowed',
              }}
            >
              <FormattedMessage id="dvEscalations.reassign.submit" />
            </button>
          </div>
        </form>
      </div>
    </>
  );
}
