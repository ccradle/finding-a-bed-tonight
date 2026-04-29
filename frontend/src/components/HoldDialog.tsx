import { useState, useEffect, useRef, type FormEvent } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { color } from '../theme/colors';
import { text, weight } from '../theme/typography';

/**
 * transitional-reentry-support slice 4 §11. Hold-creation dialog with
 * an optional client-attribution section per design D4.
 *
 * <p>UX shape (warroom H2): the dialog defaults to "no attribution"
 * mode — Confirm button is auto-focused on open, attribution section
 * is collapsed in `<details>` (warroom M1 + §11.3). An operator
 * placing a no-attribution hold experiences: click chip-button → Enter
 * → POST. One extra keystroke vs. the pre-§11 instant-POST flow.
 *
 * <p>Validation (warroom H3, layered):
 * <ol>
 *   <li>HTML5 `min="1900-01-01"` and `max={today}` on the date input.</li>
 *   <li>JS guard before submit returns early with localized error.</li>
 *   <li>Try/catch around the parent's submit handler surfaces server
 *       400 details (e.g., backend service-layer 1900 floor).</li>
 * </ol>
 *
 * <p>The Casey-reviewed `hold.clientAttributionPrivacyNote` renders
 * INSIDE the `<details>` open state (warroom M5 — privacy posture
 * visible at the moment of decision, not buried in a tooltip).
 */
export interface HoldDialogProps {
  isOpen: boolean;
  shelterName: string;
  populationType: string;
  populationTypeLabel: string;
  /** Async submit handler — receives the optional attribution. Throws on backend rejection so the dialog can display server detail. */
  onSubmit: (attribution: HoldAttribution) => Promise<void>;
  onCancel: () => void;
}

export interface HoldAttribution {
  heldForClientName?: string;
  heldForClientDob?: string;
  holdNotes?: string;
}

export function HoldDialog({
  isOpen,
  shelterName,
  populationType: _populationType,
  populationTypeLabel,
  onSubmit,
  onCancel,
}: HoldDialogProps) {
  const intl = useIntl();

  const [name, setName] = useState('');
  const [dob, setDob] = useState('');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirmButtonRef = useRef<HTMLButtonElement>(null);

  // Reset state on open transition + auto-focus Confirm.
  useEffect(() => {
    if (isOpen) {
      setName('');
      setDob('');
      setNotes('');
      setSubmitting(false);
      setError(null);
      // Defer focus to the next tick so the modal is in the DOM.
      const t = setTimeout(() => confirmButtonRef.current?.focus(), 0);
      return () => clearTimeout(t);
    }
    return undefined;
  }, [isOpen]);

  if (!isOpen) return null;

  const today = new Date().toISOString().slice(0, 10);
  const dobFloor = '1900-01-01';

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    // Layered validation (warroom H3) — JS guard.
    if (dob) {
      if (dob < dobFloor) {
        setError(intl.formatMessage({ id: 'hold.dialog.dobInvalidFloor' }));
        return;
      }
      if (dob > today) {
        setError(intl.formatMessage({ id: 'hold.dialog.dobInvalidFuture' }));
        return;
      }
    }

    const attribution: HoldAttribution = {};
    if (name.trim()) attribution.heldForClientName = name.trim();
    if (dob) attribution.heldForClientDob = dob;
    if (notes.trim()) attribution.holdNotes = notes.trim();

    setSubmitting(true);
    try {
      await onSubmit(attribution);
    } catch (err: unknown) {
      // Surface server-side validation detail (backend 400 with
      // context.detail per slice 2D verify-round-2 W2 fix).
      const apiErr = err as { context?: { detail?: string }; message?: string };
      setError(apiErr.context?.detail || apiErr.message || 'Error');
      setSubmitting(false);
    }
  };

  const handleEscape = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') onCancel();
  };

  const fieldStyle: React.CSSProperties = { marginBottom: 12 };
  const labelStyle: React.CSSProperties = {
    display: 'block', fontSize: text.sm, fontWeight: weight.semibold,
    color: color.textTertiary, marginBottom: 4,
  };
  const sublabelStyle: React.CSSProperties = {
    fontSize: text.xs, fontWeight: weight.normal,
    color: color.textMuted, marginLeft: 6,
  };
  const inputStyle: React.CSSProperties = {
    width: '100%', padding: '8px 12px', borderRadius: 6,
    border: `1.5px solid ${color.border}`, fontSize: text.base, minHeight: 38,
    boxSizing: 'border-box', fontFamily: 'inherit',
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="hold-dialog-title"
      data-testid="hold-dialog"
      onKeyDown={handleEscape}
      style={{
        position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        zIndex: 1000, padding: 16,
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          backgroundColor: color.bg, borderRadius: 12, padding: 24,
          maxWidth: 520, width: '100%', maxHeight: '90vh', overflowY: 'auto',
          boxShadow: '0 20px 60px rgba(0,0,0,0.3)',
        }}
      >
        <h2
          id="hold-dialog-title"
          style={{ margin: '0 0 8px', fontSize: text.lg, fontWeight: weight.bold, color: color.text }}
        >
          <FormattedMessage id="hold.dialog.title" />
        </h2>
        <p style={{ margin: '0 0 16px', fontSize: text.sm, color: color.textTertiary }}>
          {shelterName} — {populationTypeLabel}
        </p>

        {/* Optional attribution — collapsed by default per §11.3. The
            <details> element is keyboard + screen-reader accessible
            without custom code. */}
        <details
          data-testid="hold-attribution-toggle"
          style={{ marginBottom: 16, border: `1px solid ${color.border}`, borderRadius: 6 }}
        >
          <summary
            style={{
              padding: '10px 14px', cursor: 'pointer',
              fontSize: text.sm, fontWeight: weight.semibold, color: color.text,
            }}
          >
            <FormattedMessage id="hold.dialog.addClientDetails" />
          </summary>
          <div style={{ padding: '4px 14px 14px' }}>
            {/* Casey-reviewed privacy note — INSIDE the open <details>
                per warroom M5. Operators see the privacy posture at
                the moment they decide whether to enter PII. */}
            <p
              data-testid="hold-attribution-privacy-note"
              style={{
                margin: '0 0 12px', padding: '10px 12px', borderRadius: 6,
                backgroundColor: color.warningBg, color: color.warning,
                fontSize: text.xs, lineHeight: 1.5,
                border: `1px solid ${color.warning}`,
              }}
            >
              <FormattedMessage id="hold.clientAttributionPrivacyNote" />
            </p>

            <div style={fieldStyle}>
              <label htmlFor="hold-client-name-input" style={labelStyle}>
                <FormattedMessage id="hold.heldForClientName" />
                <span style={sublabelStyle}>
                  <FormattedMessage id="hold.heldForClientNameSublabel" />
                </span>
              </label>
              <input
                id="hold-client-name-input"
                data-testid="hold-client-name-input"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                maxLength={100}
                style={inputStyle}
              />
            </div>

            <div style={fieldStyle}>
              <label htmlFor="hold-client-dob-input" style={labelStyle}>
                <FormattedMessage id="hold.heldForClientDob" />
                <span style={sublabelStyle}>
                  <FormattedMessage id="hold.heldForClientDobSublabel" />
                </span>
              </label>
              <input
                id="hold-client-dob-input"
                data-testid="hold-client-dob-input"
                type="date"
                value={dob}
                onChange={(e) => setDob(e.target.value)}
                min={dobFloor}
                max={today}
                style={inputStyle}
              />
            </div>

            <div style={fieldStyle}>
              <label htmlFor="hold-notes-input" style={labelStyle}>
                <FormattedMessage id="hold.notes" />
              </label>
              <textarea
                id="hold-notes-input"
                data-testid="hold-notes-input"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                maxLength={500}
                rows={3}
                style={{ ...inputStyle, resize: 'vertical' }}
              />
            </div>
          </div>
        </details>

        {error && (
          <div
            role="alert"
            data-testid="hold-dialog-error"
            style={{
              backgroundColor: color.errorBg, color: color.error,
              padding: '10px 14px', borderRadius: 6, fontSize: text.sm,
              marginBottom: 12,
            }}
          >
            {error}
          </div>
        )}

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button
            type="button"
            data-testid="hold-dialog-cancel-button"
            onClick={onCancel}
            disabled={submitting}
            style={{
              padding: '10px 18px', borderRadius: 6,
              border: `1.5px solid ${color.border}`,
              backgroundColor: color.bg, color: color.text,
              fontSize: text.base, fontWeight: weight.medium,
              cursor: submitting ? 'not-allowed' : 'pointer', minHeight: 44,
            }}
          >
            <FormattedMessage id="hold.dialog.cancel" />
          </button>
          <button
            ref={confirmButtonRef}
            type="submit"
            data-testid="hold-dialog-confirm-button"
            disabled={submitting}
            style={{
              padding: '10px 18px', borderRadius: 6, border: 'none',
              backgroundColor: submitting ? color.borderMedium : color.primary,
              color: color.textInverse,
              fontSize: text.base, fontWeight: weight.bold,
              cursor: submitting ? 'not-allowed' : 'pointer', minHeight: 44,
            }}
          >
            <FormattedMessage id="hold.dialog.confirm" />
          </button>
        </div>
      </form>
    </div>
  );
}
