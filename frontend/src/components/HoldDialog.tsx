import { useState, useEffect, useRef, type FormEvent } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useAuth } from '../auth/useAuth';
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
  const { user } = useAuth();

  const [name, setName] = useState('');
  const [dob, setDob] = useState('');
  const [notes, setNotes] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const confirmButtonRef = useRef<HTMLButtonElement>(null);
  // Slice 4 §11 warroom M1 — capture the element that had focus when
  // the dialog opened so we can return focus there on close (WCAG 2.4.3
  // + APG dialog pattern). Without this, Cancel/Confirm/Esc lands focus
  // on `<body>` and a keyboard user has to tab back through the page.
  const previouslyFocusedRef = useRef<HTMLElement | null>(null);
  const formRef = useRef<HTMLFormElement>(null);

  // Reset state on open transition + auto-focus Confirm.
  useEffect(() => {
    if (isOpen) {
      previouslyFocusedRef.current = document.activeElement as HTMLElement | null;
      setName('');
      setDob('');
      setNotes('');
      setSubmitting(false);
      setError(null);
      // Defer focus to the next tick so the modal is in the DOM.
      const t = setTimeout(() => confirmButtonRef.current?.focus(), 0);
      return () => clearTimeout(t);
    }
    // On close transition, return focus to whatever opened the dialog.
    previouslyFocusedRef.current?.focus?.();
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

  // Slice 4 §11 warroom M1 + M2 — modal keyboard handler:
  //  - Esc closes (existing behavior).
  //  - Tab/Shift+Tab traps focus within the dialog form. Without the trap,
  //    `aria-modal="true"` is a lie: keyboard users tabbing past the last
  //    control land on page elements behind the dialog (WCAG 2.4.3 / APG
  //    dialog pattern). Implementation: query the form for tabbable
  //    elements at keypress time so dynamically-mounted fields (e.g.,
  //    when the user opens the `<details>` attribution section) are
  //    included automatically.
  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      onCancel();
      return;
    }
    if (e.key !== 'Tab') return;
    const form = formRef.current;
    if (!form) return;
    const tabbables = form.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), '
        + 'select:not([disabled]), textarea:not([disabled]), summary, [tabindex]:not([tabindex="-1"])',
    );
    if (tabbables.length === 0) return;
    const first = tabbables[0];
    const last = tabbables[tabbables.length - 1];
    const active = document.activeElement as HTMLElement | null;
    if (e.shiftKey && active === first) {
      e.preventDefault();
      last.focus();
    } else if (!e.shiftKey && active === last) {
      e.preventDefault();
      first.focus();
    }
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
  // v0.55 §11.3 — always-visible help text under each PII input.
  // Screen readers announce on focus via aria-describedby on the input.
  // Non-hover; renders unconditionally inside the (open-by-default) attribution details.
  const helpTextStyle: React.CSSProperties = {
    fontSize: text.xs, color: color.textTertiary,
    margin: '4px 0 0', lineHeight: 1.45,
  };

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="hold-dialog-title"
      data-testid="hold-dialog"
      onKeyDown={handleKeyDown}
      style={{
        position: 'fixed', inset: 0, backgroundColor: 'rgba(0,0,0,0.5)',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        zIndex: 1000, padding: 16,
      }}
    >
      <form
        ref={formRef}
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

        {/* Round 5 §16.C.4 — entire attribution block is gated on
            features.reentryMode. The §16.B API serialization gate is
            the primary control; this hides the input UI so a non-reentry
            tenant can't even compose PII to send. */}
        {user?.reentryMode && (
        <section data-testid="reentry-pii-fields">
          {/* v0.55 §11 Round-2 (Devon DK-RR-A12 + Tomás DK-RR-4): privacy
              note renders OUTSIDE the disclosure — always visible above
              the (collapsed-by-default) attribution toggle. Operators
              encounter the privacy posture at the moment they decide
              whether to expand. The per-input help text below each PII
              input lives INSIDE the disclosure (it is contextually tied
              to the input it describes, and aria-describedby announces
              it to screen-readers when focus enters the input — focus
              cannot enter while the disclosure is collapsed, so the
              help text being inside the disclosure is correct). */}
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
                aria-describedby="hold-client-name-help"
              />
              <p
                id="hold-client-name-help"
                data-testid="hold-help-client-name"
                style={helpTextStyle}
              >
                <FormattedMessage id="hold.help.clientName" />
              </p>
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
                aria-describedby="hold-client-dob-help"
              />
              <p
                id="hold-client-dob-help"
                data-testid="hold-help-client-dob"
                style={helpTextStyle}
              >
                <FormattedMessage id="hold.help.clientDob" />
              </p>
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
                aria-describedby="hold-notes-help"
              />
              <p
                id="hold-notes-help"
                data-testid="hold-help-notes"
                style={helpTextStyle}
              >
                <FormattedMessage id="hold.help.notes" />
              </p>
            </div>
          </div>
        </details>
        </section>
        )}

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
