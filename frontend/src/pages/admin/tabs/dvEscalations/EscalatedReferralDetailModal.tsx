import { useState, useEffect, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../../../../theme/colors';
import { text, weight } from '../../../../theme/typography';
import { api } from '../../../../services/api';
import { markNotificationsActedByPayload } from '../../../../services/notificationMarkActed';
import { primaryBtnStyle, inputStyle } from '../../styles';
import type { EscalatedReferral } from '../../../../hooks/useDvEscalationQueue';
import { ReassignSubModal } from './ReassignSubModal';

interface EscalatedReferralDetailModalProps {
  referral: EscalatedReferral;
  /** Called whenever the referral state changes (claim, release, action) so the parent refreshes the queue. */
  onChanged: () => void;
  /** Called when the user closes the modal (X button or Escape). */
  onClose: () => void;
  /** Optional: id of the currently logged-in user — controls Claim vs Release toggle. */
  currentUserId?: string;
}

type ConfirmAction = 'approve' | 'deny' | null;

/**
 * T-36 — Detail modal for a single escalated DV referral.
 *
 * <p><b>Modal accessibility</b> per archived `2026-03-26-wcag-accessibility-audit` D8:
 * `role="dialog"`, `aria-modal="true"`, `aria-labelledby` + `aria-describedby`,
 * focus moves to first interactive on open, Escape closes, focus returns to
 * trigger on close (parent's responsibility), backdrop click closes.</p>
 *
 * <p><b>Action buttons:</b> Claim (or Release if held by current user), Reassign,
 * Approve placement, Deny — each 44×44px minimum touch target. Approve/Deny use
 * the danger-variant confirmation pattern with specific verb text and a 5-min
 * undo ribbon (UI state, not server state).</p>
 *
 * <p><b>Zero PII display:</b> only shelter name, population type, household
 * size, urgency, and time-to-expiry are shown. The DTO from the backend already
 * strips callbackNumber and clientName per Marcus Webb's contract.</p>
 *
 * <p><b>PII warning on Deny reason field</b> per Marcus Webb persona note (T-36).</p>
 */
export function EscalatedReferralDetailModal({
  referral,
  onChanged,
  onClose,
  currentUserId,
}: EscalatedReferralDetailModalProps) {
  const intl = useIntl();
  const dialogRef = useRef<HTMLDivElement>(null);
  const initialFocusRef = useRef<HTMLButtonElement>(null);

  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showReassign, setShowReassign] = useState(false);
  const [confirmAction, setConfirmAction] = useState<ConfirmAction>(null);
  const [denyReason, setDenyReason] = useState('');
  /**
   * Post-action confirmation toast — honest "Approved at [shelter]" /
   * "Denied at [shelter]" message that auto-dismisses after 4 seconds.
   *
   * <p><b>Why no Undo button:</b> war room round 6 (Casey Drummond + Marcus
   * Webb) caught a chain-of-custody bug. The original implementation showed
   * "Undo within 5 minutes" but the Undo button was a no-op — the audit
   * trail said the placement was approved, the admin's mental model said
   * they'd undone it. Worse than not having Undo. A future change can add
   * deferred-commit Undo (NN/g delayed-commit pattern) once we have a
   * "pending action" backing store; for now we tell the truth.</p>
   */
  const [completedAction, setCompletedAction] = useState<string | null>(null);

  // Focus + Escape (D20 modal accessibility).
  useEffect(() => {
    initialFocusRef.current?.focus();
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        if (showReassign) return; // sub-modal handles its own escape
        if (confirmAction) {
          setConfirmAction(null);
          return;
        }
        onClose();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose, showReassign, confirmAction]);

  // Auto-dismiss the completed-action toast after 4 seconds.
  useEffect(() => {
    if (!completedAction) return;
    const timer = setTimeout(() => setCompletedAction(null), 4000);
    return () => clearTimeout(timer);
  }, [completedAction]);

  const isOwnedByCurrentUser =
    !!currentUserId && referral.claimedByAdminId === currentUserId;

  const handleClaim = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/dv-referrals/${referral.id}/claim`);
      onChanged();
    } catch (err: unknown) {
      const apiErr = err as { message?: string; status?: number };
      if (apiErr.status === 409) {
        setError(intl.formatMessage({ id: 'dvEscalations.error.claimedByOther' }));
      } else {
        setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const handleRelease = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.post(`/api/v1/dv-referrals/${referral.id}/release`);
      onChanged();
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
    } finally {
      setSubmitting(false);
    }
  };

  const handleApprove = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await api.patch(`/api/v1/dv-referrals/${referral.id}/accept`);
      setCompletedAction(intl.formatMessage(
        { id: 'dvEscalations.modal.approved' },
        { shelterName: referral.shelterName },
      ));
      setConfirmAction(null);
      onChanged();
      // Phase 3 task 7.2 — admin approve is a terminal action. Fan-out
      // markActed across the escalation chain for this referralId.
      markNotificationsActedByPayload('referralId', referral.id, 'acted').catch(() => { /* best-effort */ });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeny = async () => {
    if (!denyReason.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      await api.patch(`/api/v1/dv-referrals/${referral.id}/reject`, { reason: denyReason.trim() });
      setCompletedAction(intl.formatMessage(
        { id: 'dvEscalations.modal.denied' },
        { shelterName: referral.shelterName },
      ));
      setConfirmAction(null);
      setDenyReason('');
      onChanged();
      // Phase 3 task 7.2 — deny is also terminal (admin explicitly declined).
      markNotificationsActedByPayload('referralId', referral.id, 'acted').catch(() => { /* best-effort */ });
    } catch (err: unknown) {
      const apiErr = err as { message?: string };
      setError(apiErr.message || intl.formatMessage({ id: 'dvEscalations.error.actionFailed' }));
    } finally {
      setSubmitting(false);
    }
  };

  // Backdrop click should NOT discard typed input on a confirm sub-state OR
  // an open Reassign sub-modal. War room rounds 6+7 — Marcus Webb + NN/g:
  // backdrop click that drops a half-typed deny reason or reassign reason
  // is the kind of thing that gets caught at pilot demo. The Escape key
  // already does the right thing (closes the active sub-state first); the
  // backdrop must match.
  //
  // The order matters: if showReassign is true, the user is interacting
  // with the sub-modal which has its own backdrop at z-index 1100. Clicks
  // on the OUTER backdrop (z-index 1000) reach this handler — without the
  // guard they would close the entire modal stack and lose the reassign
  // form input.
  const handleBackdropClick = () => {
    if (showReassign) return;       // user is in the Reassign sub-modal — preserve its input
    if (confirmAction !== null) return; // user is mid-confirm — preserve typed deny reason
    onClose();
  };

  return (
    <>
      {/* Backdrop */}
      <div
        onClick={handleBackdropClick}
        data-testid="dv-escalation-detail-backdrop"
        style={{
          position: 'fixed', inset: 0,
          backgroundColor: 'rgba(0,0,0,0.4)',
          zIndex: 1000,
        }}
      />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="dv-escalation-modal-title"
        aria-describedby="dv-escalation-modal-body"
        data-testid="dv-escalation-detail-modal"
        style={{
          position: 'fixed',
          top: '50%', left: '50%',
          transform: 'translate(-50%, -50%)',
          width: '92vw', maxWidth: 560, maxHeight: '88vh',
          backgroundColor: color.bg,
          borderRadius: 14,
          boxShadow: '0 20px 60px rgba(0,0,0,0.35)',
          zIndex: 1001,
          display: 'flex', flexDirection: 'column', overflow: 'hidden',
        }}
      >
        <div style={{
          padding: '16px 20px',
          borderBottom: `1px solid ${color.border}`,
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <h2
            id="dv-escalation-modal-title"
            style={{ margin: 0, fontSize: text.lg, fontWeight: weight.bold, color: color.text }}
          >
            <FormattedMessage id="dvEscalations.modal.title" />
          </h2>
          <button
            onClick={onClose}
            aria-label={intl.formatMessage({ id: 'dvEscalations.modal.close' })}
            data-testid="dv-escalation-detail-close"
            style={{
              background: 'none', border: 'none', cursor: 'pointer',
              fontSize: text.xl, color: color.textMuted,
              minWidth: 44, minHeight: 44,
            }}
          >×</button>
        </div>

        {/* Honest "completed" toast — auto-dismisses after 4 seconds.
            War room round 6: replaces the misleading "Undo" banner per
            Casey Drummond / Marcus Webb chain-of-custody concern. The
            audit trail is the source of truth; this UI is just an
            acknowledgement, not a reversible action. */}
        {completedAction && (
          <div
            role="status"
            data-testid="dv-escalation-completed-toast"
            style={{
              padding: '10px 20px',
              backgroundColor: color.successBg,
              color: color.text,
              borderBottom: `1px solid ${color.successBorder}`,
              fontSize: text.sm,
              fontWeight: weight.medium,
            }}
          >
            ✓ {completedAction}
          </div>
        )}

        <div id="dv-escalation-modal-body" style={{ padding: 20, overflowY: 'auto', flex: 1 }}>
          {/* Shelter + chain-broken badge */}
          <div style={{ marginBottom: 16 }}>
            <div style={{ fontSize: text.lg, fontWeight: weight.bold, color: color.text, marginBottom: 4 }}>
              {referral.shelterName}
            </div>
            {referral.escalationChainBroken && (
              <span
                data-testid="dv-escalation-detail-chain-broken-badge"
                style={{
                  display: 'inline-block',
                  padding: '4px 10px',
                  borderRadius: 12,
                  fontSize: text.xs,
                  fontWeight: weight.semibold,
                  backgroundColor: color.dvBg,
                  color: color.dvText,
                  border: `1px solid ${color.dvBorder}`,
                }}
              >
                {referral.claimedByAdminName ? (
                  <FormattedMessage
                    id="dvEscalations.queue.owner"
                    values={{ name: referral.claimedByAdminName }}
                  />
                ) : (
                  <FormattedMessage id="dvEscalations.queue.ownerUnknown" />
                )}
              </span>
            )}
          </div>

          {/* Referral facts — zero PII */}
          <dl style={{
            display: 'grid',
            gridTemplateColumns: 'auto 1fr',
            gap: '8px 16px',
            margin: 0,
            marginBottom: 20,
            fontSize: text.sm,
          }}>
            <dt style={{ color: color.textSecondary, fontWeight: weight.semibold }}>
              <FormattedMessage id="dvEscalations.queue.col.population" />
            </dt>
            <dd style={{ margin: 0, color: color.text }}>{referral.populationType}</dd>

            <dt style={{ color: color.textSecondary, fontWeight: weight.semibold }}>
              <FormattedMessage id="dvEscalations.queue.col.household" />
            </dt>
            <dd style={{ margin: 0, color: color.text }}>{referral.householdSize}</dd>

            <dt style={{ color: color.textSecondary, fontWeight: weight.semibold }}>
              <FormattedMessage id="dvEscalations.queue.col.urgency" />
            </dt>
            <dd style={{ margin: 0, color: color.text }}>{referral.urgency}</dd>

            <dt style={{ color: color.textSecondary, fontWeight: weight.semibold }}>
              <FormattedMessage id="dvEscalations.queue.col.timeLeft" />
            </dt>
            <dd style={{ margin: 0, color: color.text }}>
              {referral.remainingMinutes <= 0 ? (
                <FormattedMessage id="dvEscalations.queue.expired" />
              ) : (
                <FormattedMessage
                  id="dvEscalations.queue.minutesLeft"
                  values={{ minutes: referral.remainingMinutes }}
                />
              )}
            </dd>

            <dt style={{ color: color.textSecondary, fontWeight: weight.semibold }}>
              <FormattedMessage id="dvEscalations.queue.col.coordinator" />
            </dt>
            <dd style={{ margin: 0, color: color.text }}>
              {referral.assignedCoordinatorName ?? <FormattedMessage id="dvEscalations.queue.unassigned" />}
            </dd>

            <dt style={{ color: color.textSecondary, fontWeight: weight.semibold }}>
              <FormattedMessage id="dvEscalations.queue.col.status" />
            </dt>
            <dd style={{ margin: 0, color: color.text }}>
              {referral.claimedByAdminName ? (
                <FormattedMessage
                  id="dvEscalations.queue.claimedBy"
                  values={{ name: referral.claimedByAdminName }}
                />
              ) : (
                <FormattedMessage id="dvEscalations.queue.unclaimed" />
              )}
            </dd>
          </dl>

          {error && (
            <div
              role="alert"
              data-testid="dv-escalation-detail-error"
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

          {/* Confirm sub-state for Approve / Deny */}
          {confirmAction === 'approve' && (
            <div data-testid="dv-escalation-confirm-approve" style={{
              padding: 16,
              border: `2px solid ${color.successBorder}`,
              borderRadius: 10,
              backgroundColor: color.successBg,
              marginBottom: 12,
            }}>
              <p style={{ margin: 0, marginBottom: 12, fontSize: text.base, color: color.text }}>
                <FormattedMessage
                  id="dvEscalations.modal.approveVerb"
                  values={{ shelterName: referral.shelterName }}
                />
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => setConfirmAction(null)}
                  data-testid="dv-escalation-confirm-approve-cancel"
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
                  onClick={handleApprove}
                  disabled={submitting}
                  data-testid="dv-escalation-confirm-approve-submit"
                  style={{
                    ...primaryBtnStyle,
                    backgroundColor: color.success,
                    opacity: submitting ? 0.5 : 1,
                  }}
                >
                  <FormattedMessage
                    id="dvEscalations.modal.approveVerb"
                    values={{ shelterName: referral.shelterName }}
                  />
                </button>
              </div>
            </div>
          )}

          {confirmAction === 'deny' && (
            <div data-testid="dv-escalation-confirm-deny" style={{
              padding: 16,
              border: `2px solid ${color.errorBorder}`,
              borderRadius: 10,
              backgroundColor: color.errorBg,
              marginBottom: 12,
            }}>
              <p style={{ margin: 0, marginBottom: 12, fontSize: text.base, color: color.text }}>
                <FormattedMessage
                  id="dvEscalations.modal.denyVerb"
                  values={{ shelterName: referral.shelterName }}
                />
              </p>

              {/* PII warning above the reason field — Marcus Webb persona note */}
              <div style={{
                padding: 10, marginBottom: 8,
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

              <label style={{ display: 'block', marginBottom: 12 }}>
                <span style={{
                  display: 'block', marginBottom: 4,
                  fontSize: text.xs, fontWeight: weight.semibold, color: color.textSecondary,
                }}>
                  <FormattedMessage id="dvEscalations.modal.denyReasonLabel" />
                </span>
                <textarea
                  value={denyReason}
                  onChange={(e) => setDenyReason(e.target.value)}
                  maxLength={500}
                  rows={3}
                  data-testid="dv-escalation-deny-reason"
                  style={{ ...inputStyle, fontFamily: 'inherit', resize: 'vertical' }}
                />
              </label>

              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button
                  onClick={() => { setConfirmAction(null); setDenyReason(''); }}
                  data-testid="dv-escalation-confirm-deny-cancel"
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
                  onClick={handleDeny}
                  disabled={submitting || !denyReason.trim()}
                  data-testid="dv-escalation-confirm-deny-submit"
                  style={{
                    ...primaryBtnStyle,
                    backgroundColor: color.errorMid,
                    opacity: submitting || !denyReason.trim() ? 0.5 : 1,
                    cursor: submitting || !denyReason.trim() ? 'not-allowed' : 'pointer',
                  }}
                >
                  <FormattedMessage
                    id="dvEscalations.modal.denyVerb"
                    values={{ shelterName: referral.shelterName }}
                  />
                </button>
              </div>
            </div>
          )}

          {/* Action row — only when not in a confirm sub-state */}
          {!confirmAction && (
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
              {isOwnedByCurrentUser ? (
                <button
                  ref={initialFocusRef}
                  onClick={handleRelease}
                  disabled={submitting}
                  data-testid="dv-escalation-detail-release"
                  style={{
                    ...primaryBtnStyle,
                    backgroundColor: color.bgSecondary,
                    color: color.text,
                    border: `2px solid ${color.border}`,
                    flex: '1 1 140px',
                  }}
                >
                  <FormattedMessage id="dvEscalations.action.release" />
                </button>
              ) : (
                <button
                  ref={initialFocusRef}
                  onClick={handleClaim}
                  disabled={submitting}
                  data-testid="dv-escalation-detail-claim"
                  style={{
                    ...primaryBtnStyle,
                    flex: '1 1 140px',
                    opacity: submitting ? 0.6 : 1,
                  }}
                >
                  <FormattedMessage id="dvEscalations.action.claim" />
                </button>
              )}

              <button
                onClick={() => setShowReassign(true)}
                disabled={submitting}
                data-testid="dv-escalation-detail-reassign"
                style={{
                  ...primaryBtnStyle,
                  backgroundColor: color.bgSecondary,
                  color: color.text,
                  border: `2px solid ${color.border}`,
                  flex: '1 1 140px',
                }}
              >
                <FormattedMessage id="dvEscalations.action.reassign" />
              </button>

              <button
                onClick={() => setConfirmAction('approve')}
                disabled={submitting}
                data-testid="dv-escalation-detail-approve"
                style={{
                  ...primaryBtnStyle,
                  // color.successMid (#15803d in dark mode, #166534 in light) — the
                  // button-fill variant of the success token. Using bare color.success
                  // shipped #42be65 on dark mode (Carbon Green-40) which is a TEXT
                  // color and gives only 2.39:1 with white-text fills (axe-core
                  // 12.3 dark scan during Phase 4 caught this).
                  backgroundColor: color.successMid,
                  flex: '1 1 140px',
                }}
              >
                <FormattedMessage id="dvEscalations.action.approve" />
              </button>

              <button
                onClick={() => setConfirmAction('deny')}
                disabled={submitting}
                data-testid="dv-escalation-detail-deny"
                style={{
                  ...primaryBtnStyle,
                  backgroundColor: color.errorMid,
                  flex: '1 1 140px',
                }}
              >
                <FormattedMessage id="dvEscalations.action.deny" />
              </button>
            </div>
          )}
        </div>
      </div>

      {showReassign && (
        <ReassignSubModal
          referralId={referral.id}
          shelterName={referral.shelterName}
          onCancel={() => setShowReassign(false)}
          onSuccess={() => {
            setShowReassign(false);
            onChanged();
          }}
        />
      )}
    </>
  );
}
