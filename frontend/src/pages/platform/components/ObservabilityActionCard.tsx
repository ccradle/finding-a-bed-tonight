/**
 * Inline-edit card for observability platform-config fields. Replaces the
 * window.prompt() flow that warroom round 6 (2026-05-03) flagged as a UX
 * antipattern.
 *
 * Resting state — looks like a regular {@link PlatformActionCard}: title,
 * description, "Current: …" line, single Edit button.
 *
 * Editing state — same card chrome but body swaps to a small accessible
 * form:
 *  - field-type-appropriate input pre-populated with current value
 *      - toggle  → switch implementing the W3C ARIA APG Switch Pattern
 *                  (role="switch" + aria-checked, label state-stable per
 *                  the APG: "the label on a switch does not change when
 *                  its state changes")
 *      - number  → &lt;input type="number" min max step="1"&gt; with bounds
 *                  hint as aria-describedby + JS guard against out-of-range
 *      - url     → &lt;input type="url" placeholder=""&gt; with URL.canParse
 *                  inline validation
 *  - justification &lt;textarea&gt; (required, ≥1 non-whitespace char)
 *  - inline error region (role="alert") for client-side and backend errors
 *  - For destructive actions: clicking Save swaps the form into a 2-step
 *    confirm panel ("Confirm: Title → newValue?") with Confirm + Cancel,
 *    submitting only after explicit confirm. No native window.prompt /
 *    window.confirm anywhere.
 *  - Saved toast (role="status") on success. Card returns to resting state
 *    and the parent re-fetches the snapshot so the "Current: …" line
 *    reflects the just-applied write.
 *
 * Sources grounding the design (warroom round 6 sources):
 *  - W3C ARIA APG — Switch Pattern (role="switch", state-stable label,
 *    Space toggles)
 *  - MDN — ARIA switch role
 *  - shadcn/ui Form Patterns — type-appropriate inputs, data-invalid +
 *    aria-invalid for validation states
 *  - WCAG 2.2 — accessible name on every control, min-height 44px touch
 *    targets, focus management on mode transitions
 */

import { useState, useEffect, useRef, useCallback } from 'react';
import { color } from '../../../theme/colors';
import type { PlatformAction } from '../platformActions';
import { platformFetch } from '../helpers/platformApi';

export type ObsErrorParse =
  | { kind: 'out-of-range'; message: string }
  | { kind: 'malformed-endpoint'; message: string }
  | { kind: 'type-mismatch'; message: string }
  | { kind: 'unknown-field'; message: string }
  | { kind: 'missing-justification'; message: string }
  | { kind: 'forbidden'; message: string }
  | { kind: 'generic'; message?: string };

/**
 * Pure helper extracted for Vitest coverage. Maps a backend error response
 * (already parsed JSON) to a typed ObsErrorParse. Caller renders a
 * localized message based on `kind`.
 *
 * Codes pinned to ErrorCodes.java (PLATFORM_OBSERVABILITY_*,
 * 'missing_justification') so a backend rename surfaces in tests.
 */
export function parseObservabilityError(
  status: number,
  body: { error?: string; message?: string; context?: { errorCode?: string } } | null,
): ObsErrorParse {
  const code = body?.context?.errorCode;
  if (code === 'platform.observability.intervalOutOfRange') {
    return { kind: 'out-of-range', message: body?.message ?? 'Value is out of range.' };
  }
  if (code === 'platform.observability.tracingEndpointMalformed') {
    return { kind: 'malformed-endpoint', message: body?.message ?? 'URL is malformed.' };
  }
  if (code === 'platform.observability.fieldTypeMismatch') {
    return { kind: 'type-mismatch', message: body?.message ?? 'Value has the wrong type.' };
  }
  if (code === 'platform.observability.unknownField') {
    return { kind: 'unknown-field', message: body?.message ?? 'Unknown field.' };
  }
  if (code === 'missing_justification') {
    return { kind: 'missing-justification', message: 'Justification is required.' };
  }
  if (status === 403) {
    return { kind: 'forbidden', message: 'You do not have permission for this action.' };
  }
  return { kind: 'generic', message: body?.message };
}

/** Resting-state value formatter — same shape the dashboard uses. */
function formatCurrent(action: PlatformAction, raw: boolean | number | string | undefined): string {
  if (raw === undefined || raw === null) return '—';
  if (action.fieldType === 'toggle') return raw ? 'enabled' : 'disabled';
  if (action.fieldType === 'number') return `${raw} ${action.unit ?? ''}`.trim();
  return String(raw);
}

interface Props {
  action: PlatformAction;
  /** Current persisted value, fetched by the parent dashboard. */
  currentValue: boolean | number | string | undefined;
  /**
   * Called after a successful PUT so the parent can re-fetch the
   * platform_config snapshot and refresh every card's "Current: …" line.
   */
  onSaved: () => void;
}

export function ObservabilityActionCard({ action, currentValue, onSaved }: Props) {
  const [editing, setEditing] = useState(false);
  // confirmStep is only used for destructive actions: form → confirm → submit.
  const [confirmStep, setConfirmStep] = useState(false);
  const [saving, setSaving] = useState(false);
  const [savedToast, setSavedToast] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Drafts. Booleans default to current value; numbers use a string so
  // the operator can clear the input without hitting NaN; URLs are strings.
  const [draftBool, setDraftBool] = useState<boolean>(false);
  const [draftText, setDraftText] = useState<string>('');
  const [draftJustification, setDraftJustification] = useState('');

  // Focus management — when entering edit mode, focus the primary input;
  // when entering confirm step, focus the confirm button (matches warroom
  // round 3 H2 pattern from DvPolicySettings).
  const inputRef = useRef<HTMLInputElement | HTMLButtonElement | null>(null);
  const confirmButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (editing && !confirmStep) inputRef.current?.focus();
  }, [editing, confirmStep]);
  useEffect(() => {
    if (confirmStep) confirmButtonRef.current?.focus();
  }, [confirmStep]);

  const enterEdit = useCallback(() => {
    setError(null);
    setSavedToast(false);
    setConfirmStep(false);
    // Pre-populate drafts from the current value so the operator edits
    // from a known starting point, not a blank field.
    if (action.fieldType === 'toggle') {
      setDraftBool(typeof currentValue === 'boolean' ? currentValue : false);
    } else {
      setDraftText(currentValue !== undefined && currentValue !== null ? String(currentValue) : '');
    }
    setDraftJustification('');
    setEditing(true);
  }, [action.fieldType, currentValue]);

  const cancelEdit = useCallback(() => {
    setEditing(false);
    setConfirmStep(false);
    setError(null);
  }, []);

  // Local validation — runs on every render to drive the Save button's
  // disabled state. Returns null if valid, else the user-facing message.
  const validate = (): string | null => {
    if (draftJustification.trim().length === 0) {
      return 'Justification is required.';
    }
    if (action.fieldType === 'number') {
      if (draftText.trim() === '') return 'Enter a number.';
      if (!/^\d+$/.test(draftText.trim())) return 'Whole numbers only.';
      const n = parseInt(draftText.trim(), 10);
      const min = action.min ?? 1;
      const max = action.max ?? 1440;
      if (n < min || n > max) return `Must be between ${min} and ${max}.`;
    }
    if (action.fieldType === 'url') {
      const v = draftText.trim();
      if (v.length === 0) return 'Enter a URL.';
      // URL.canParse is the modern accepted check (Node 19.9+, browsers
      // 2023+). Fallback: try URL constructor.
      let parsed: URL | null = null;
      try {
        parsed = new URL(v);
      } catch {
        return 'URL is malformed.';
      }
      if (!parsed.protocol || !parsed.host) return 'URL must include a scheme and host.';
    }
    return null;
  };
  const validationError = validate();

  const buildBody = (): Record<string, unknown> | null => {
    if (!action.fieldKey) return null;
    if (action.fieldType === 'toggle') {
      return { [action.fieldKey]: draftBool };
    }
    if (action.fieldType === 'number') {
      return { [action.fieldKey]: parseInt(draftText.trim(), 10) };
    }
    if (action.fieldType === 'url') {
      return { [action.fieldKey]: draftText.trim() };
    }
    return null;
  };

  const requestSave = () => {
    if (validationError) return;
    if (action.dangerLevel === 'destructive') {
      // Two-step inline confirm — no native confirm dialog.
      setConfirmStep(true);
    } else {
      void doSave();
    }
  };

  const doSave = async () => {
    setSaving(true);
    setError(null);
    const body = buildBody();
    if (!body) {
      setError('Internal error: unknown field.');
      setSaving(false);
      return;
    }
    try {
      const resp = await platformFetch(action.endpoint, {
        method: action.method,
        headers: {
          'Content-Type': 'application/json',
          'X-Platform-Justification': draftJustification.trim(),
        },
        body: JSON.stringify(body),
      });
      if (resp.ok) {
        setSavedToast(true);
        setEditing(false);
        setConfirmStep(false);
        onSaved();
      } else {
        const errorBody = await resp.json().catch(() => null);
        const parsed = parseObservabilityError(resp.status, errorBody);
        setError(parsed.message ?? 'Save failed.');
        setConfirmStep(false);
      }
    } catch {
      setError('Network error. Try again.');
      setConfirmStep(false);
    } finally {
      setSaving(false);
    }
  };

  const titleId = `obs-card-${action.id}-title`;
  const descId = `obs-card-${action.id}-desc`;
  const inputId = `obs-card-${action.id}-input`;
  const justifId = `obs-card-${action.id}-justification`;
  const boundsId = `obs-card-${action.id}-bounds`;

  // ---------- RESTING STATE ---------------------------------------------
  if (!editing) {
    return (
      <div
        style={{
          border: `1px solid ${color.border}`,
          borderRadius: '8px',
          padding: '1rem',
          backgroundColor: color.bgSecondary,
        }}
        data-testid={`platform-action-${action.id}`}
      >
        <h3 id={titleId} style={{ marginTop: 0, marginBottom: '0.5rem', fontSize: '1rem' }}>
          {action.title}
        </h3>
        <p
          id={descId}
          style={{
            marginTop: 0,
            marginBottom: '0.75rem',
            fontSize: '0.875rem',
            color: color.textSecondary,
          }}
        >
          {action.description}
        </p>
        <p
          style={{
            marginTop: 0,
            marginBottom: '0.75rem',
            fontSize: '0.875rem',
            color: color.text,
            fontWeight: 600,
            wordBreak: 'break-all',
          }}
          data-testid={`platform-action-${action.id}-current`}
        >
          Current: <span style={{ fontWeight: 400 }}>{formatCurrent(action, currentValue)}</span>
        </p>
        {savedToast && (
          <p
            role="status"
            data-testid={`platform-action-${action.id}-saved`}
            style={{
              marginTop: 0,
              marginBottom: '0.75rem',
              fontSize: '0.875rem',
              color: color.successBright,
              fontWeight: 600,
            }}
          >
            Saved.
          </p>
        )}
        <button
          type="button"
          onClick={enterEdit}
          data-testid={`platform-action-${action.id}-edit`}
          style={{
            padding: '0.5rem 0.875rem',
            fontSize: '0.875rem',
            fontWeight: 600,
            borderRadius: '4px',
            border: 'none',
            cursor: 'pointer',
            backgroundColor:
              action.dangerLevel === 'destructive' ? color.error : color.primary,
            color: color.textInverse,
          }}
        >
          {action.buttonLabel}
        </button>
      </div>
    );
  }

  // ---------- EDIT FORM (or confirm step) -------------------------------
  return (
    <div
      style={{
        border: `1px solid ${color.border}`,
        borderRadius: '8px',
        padding: '1rem',
        backgroundColor: color.bgSecondary,
      }}
      data-testid={`platform-action-${action.id}`}
      // Esc anywhere in the card cancels the edit (warroom round 3 H1).
      onKeyDown={(e) => {
        if (e.key === 'Escape' && !saving) cancelEdit();
      }}
    >
      <h3 id={titleId} style={{ marginTop: 0, marginBottom: '0.5rem', fontSize: '1rem' }}>
        {action.title}
      </h3>
      <p
        id={descId}
        style={{
          marginTop: 0,
          marginBottom: '0.75rem',
          fontSize: '0.875rem',
          color: color.textSecondary,
        }}
      >
        {action.description}
      </p>

      {confirmStep ? (
        <ConfirmPanel
          action={action}
          newValueDisplay={formatCurrent(action, action.fieldType === 'toggle' ? draftBool : draftText.trim())}
          oldValueDisplay={formatCurrent(action, currentValue)}
          saving={saving}
          confirmButtonRef={confirmButtonRef}
          onConfirm={() => void doSave()}
          onCancel={() => setConfirmStep(false)}
        />
      ) : (
        <>
          {/* Field input — type-switched */}
          {action.fieldType === 'toggle' && (
            <div style={{ marginBottom: '0.75rem', display: 'flex', alignItems: 'center', gap: 12 }}>
              <button
                type="button"
                role="switch"
                aria-checked={draftBool}
                aria-labelledby={titleId}
                aria-describedby={descId}
                ref={(el) => { inputRef.current = el; }}
                onClick={() => setDraftBool((v) => !v)}
                onKeyDown={(e) => {
                  // Per W3C ARIA APG: Space toggles. Enter is optional but
                  // most operators expect it too.
                  if (e.key === ' ' || e.key === 'Enter') {
                    e.preventDefault();
                    setDraftBool((v) => !v);
                  }
                }}
                disabled={saving}
                data-testid={`platform-action-${action.id}-toggle`}
                style={{
                  // Visual track sized so the knob looks proportionate
                  // (iOS-style ~1.85:1 track-to-knob ratio).
                  width: 52,
                  height: 30,
                  borderRadius: 999,
                  border: `2px solid ${color.borderMedium}`,
                  // State-stable: color shifts but the LABEL above does not (APG).
                  backgroundColor: draftBool ? color.primary : color.bg,
                  position: 'relative',
                  cursor: saving ? 'not-allowed' : 'pointer',
                  padding: 0,
                  transition: 'background-color 120ms ease',
                  // No minHeight here — that previously inflated the
                  // visual track to 44 while the knob stayed anchored
                  // at top:2, looking tiny inside an oversized track.
                  // 30×52 still exceeds WCAG 2.5.5 (AA) 24×24 target;
                  // a wrapper would be needed for AAA-44 touch targets,
                  // captured as a follow-up if needed.
                  flexShrink: 0,
                }}
              >
                {/* Knob — vertically centered via top:50% + translateY,
                    horizontally slid via translateX so the transition
                    animates smoothly (margin:auto wouldn't animate). */}
                <span
                  aria-hidden="true"
                  style={{
                    position: 'absolute',
                    top: '50%',
                    left: 2,
                    width: 22,
                    height: 22,
                    borderRadius: 999,
                    backgroundColor: draftBool ? color.textInverse : color.borderMedium,
                    transform: `translateY(-50%) translateX(${draftBool ? 22 : 0}px)`,
                    transition: 'transform 120ms ease, background-color 120ms ease',
                    boxShadow: '0 1px 3px rgba(0,0,0,0.18)',
                  }}
                />
              </button>
              <span style={{ fontSize: '0.875rem', color: color.text }}>
                {draftBool ? 'Enabled' : 'Disabled'}
              </span>
            </div>
          )}

          {action.fieldType === 'number' && (
            <div style={{ marginBottom: '0.75rem' }}>
              <label
                htmlFor={inputId}
                style={{
                  display: 'block',
                  fontSize: '0.75rem',
                  fontWeight: 600,
                  marginBottom: 4,
                  color: color.textSecondary,
                }}
              >
                New value
              </label>
              {/* Tight inline group: [input] [unit]. Width sized to fit
                  1-4 digits + the native spinner arrows + padding. The
                  unit suffix lives outside the input so the operator sees
                  the same "60 minutes" reading shape both at rest and
                  while typing. */}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <input
                  id={inputId}
                  ref={(el) => { inputRef.current = el; }}
                  type="number"
                  inputMode="numeric"
                  min={action.min}
                  max={action.max}
                  step={1}
                  value={draftText}
                  onChange={(e) => setDraftText(e.target.value)}
                  disabled={saving}
                  aria-describedby={boundsId}
                  aria-invalid={validationError !== null && draftText.length > 0}
                  data-testid={`platform-action-${action.id}-input`}
                  style={{
                    padding: '8px 10px',
                    border: `1px solid ${color.borderMedium}`,
                    borderRadius: 8,
                    fontSize: '0.875rem',
                    width: 96,
                    boxSizing: 'border-box',
                    textAlign: 'right',
                  }}
                />
                {action.unit && (
                  <span
                    aria-hidden="true"
                    style={{ fontSize: '0.875rem', color: color.textSecondary }}
                  >
                    {action.unit}
                  </span>
                )}
              </div>
              <p
                id={boundsId}
                style={{
                  margin: '4px 0 0',
                  fontSize: '0.75rem',
                  color: color.textTertiary,
                }}
              >
                Between {action.min ?? 1} and {action.max ?? 1440} {action.unit ?? ''}.
              </p>
            </div>
          )}

          {action.fieldType === 'url' && (
            <div style={{ marginBottom: '0.75rem' }}>
              <label
                htmlFor={inputId}
                style={{
                  display: 'block',
                  fontSize: '0.75rem',
                  fontWeight: 600,
                  marginBottom: 4,
                  color: color.textSecondary,
                }}
              >
                New URL
              </label>
              <input
                id={inputId}
                ref={(el) => { inputRef.current = el; }}
                type="url"
                value={draftText}
                onChange={(e) => setDraftText(e.target.value)}
                placeholder={action.placeholder}
                disabled={saving}
                aria-invalid={validationError !== null && draftText.length > 0}
                data-testid={`platform-action-${action.id}-input`}
                style={{
                  padding: '8px 12px',
                  border: `1px solid ${color.borderMedium}`,
                  borderRadius: 8,
                  fontSize: '0.875rem',
                  width: '100%',
                  boxSizing: 'border-box',
                }}
              />
            </div>
          )}

          {/* Justification */}
          <div style={{ marginBottom: '0.75rem' }}>
            <label
              htmlFor={justifId}
              style={{
                display: 'block',
                fontSize: '0.75rem',
                fontWeight: 600,
                marginBottom: 4,
                color: color.textSecondary,
              }}
            >
              Justification (recorded in audit trail)
            </label>
            <textarea
              id={justifId}
              value={draftJustification}
              onChange={(e) => setDraftJustification(e.target.value)}
              disabled={saving}
              rows={2}
              data-testid={`platform-action-${action.id}-justification`}
              style={{
                padding: '8px 12px',
                border: `1px solid ${color.borderMedium}`,
                borderRadius: 8,
                fontSize: '0.875rem',
                width: '100%',
                minHeight: 60,
                boxSizing: 'border-box',
                fontFamily: 'inherit',
              }}
            />
          </div>

          {/* Errors */}
          {error && (
            <p
              role="alert"
              data-testid={`platform-action-${action.id}-error`}
              style={{
                marginTop: 0,
                marginBottom: '0.75rem',
                padding: '8px 12px',
                fontSize: '0.875rem',
                color: color.error,
                backgroundColor: color.errorBg,
                border: `1px solid ${color.errorBorder}`,
                borderRadius: 8,
              }}
            >
              {error}
            </p>
          )}
          {!error && validationError && draftJustification.length + draftText.length > 0 && (
            <p
              data-testid={`platform-action-${action.id}-validation`}
              style={{
                marginTop: 0,
                marginBottom: '0.75rem',
                fontSize: '0.75rem',
                color: color.warning,
              }}
            >
              {validationError}
            </p>
          )}

          {/* Buttons */}
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              onClick={cancelEdit}
              disabled={saving}
              data-testid={`platform-action-${action.id}-cancel`}
              style={{
                padding: '0.5rem 0.875rem',
                fontSize: '0.875rem',
                fontWeight: 600,
                borderRadius: '4px',
                border: `2px solid ${color.border}`,
                cursor: saving ? 'not-allowed' : 'pointer',
                backgroundColor: color.bg,
                color: color.text,
              }}
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={requestSave}
              disabled={saving || validationError !== null}
              data-testid={`platform-action-${action.id}-save`}
              style={{
                padding: '0.5rem 0.875rem',
                fontSize: '0.875rem',
                fontWeight: 600,
                borderRadius: '4px',
                border: 'none',
                cursor: saving || validationError ? 'not-allowed' : 'pointer',
                backgroundColor:
                  saving || validationError
                    ? color.borderMedium
                    : action.dangerLevel === 'destructive'
                      ? color.error
                      : color.primary,
                color: color.textInverse,
              }}
            >
              {saving ? 'Saving…' : action.dangerLevel === 'destructive' ? 'Review change' : 'Save'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * Two-step destructive confirmation panel. Replaces window.confirm() —
 * shows the action title, the OLD value, and the NEW value side by side
 * so the operator can verify the change before it's applied.
 */
function ConfirmPanel({
  action,
  oldValueDisplay,
  newValueDisplay,
  saving,
  confirmButtonRef,
  onConfirm,
  onCancel,
}: {
  action: PlatformAction;
  oldValueDisplay: string;
  newValueDisplay: string;
  saving: boolean;
  confirmButtonRef: React.RefObject<HTMLButtonElement | null>;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div
      role="region"
      aria-label="Confirm destructive change"
      data-testid={`platform-action-${action.id}-confirm-panel`}
      style={{
        marginBottom: '0.75rem',
        padding: '12px',
        backgroundColor: color.warningBg,
        border: `1px solid ${color.warning}`,
        borderRadius: 8,
      }}
    >
      <p style={{ margin: 0, marginBottom: 8, fontSize: '0.875rem', fontWeight: 600 }}>
        ⚠ Destructive change. Review before applying.
      </p>
      <p style={{ margin: 0, marginBottom: 4, fontSize: '0.875rem' }}>
        <strong>From:</strong> <span style={{ fontFamily: 'monospace' }}>{oldValueDisplay}</span>
      </p>
      <p style={{ margin: 0, marginBottom: 12, fontSize: '0.875rem', wordBreak: 'break-all' }}>
        <strong>To:</strong> <span style={{ fontFamily: 'monospace' }}>{newValueDisplay}</span>
      </p>
      <div style={{ display: 'flex', gap: 8 }}>
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          data-testid={`platform-action-${action.id}-confirm-cancel`}
          style={{
            padding: '0.5rem 0.875rem',
            fontSize: '0.875rem',
            fontWeight: 600,
            borderRadius: '4px',
            border: `2px solid ${color.border}`,
            cursor: saving ? 'not-allowed' : 'pointer',
            backgroundColor: color.bg,
            color: color.text,
          }}
        >
          Back
        </button>
        <button
          ref={confirmButtonRef}
          type="button"
          onClick={onConfirm}
          disabled={saving}
          data-testid={`platform-action-${action.id}-confirm-submit`}
          style={{
            padding: '0.5rem 0.875rem',
            fontSize: '0.875rem',
            fontWeight: 600,
            borderRadius: '4px',
            border: 'none',
            cursor: saving ? 'not-allowed' : 'pointer',
            backgroundColor: saving ? color.borderMedium : color.error,
            color: color.textInverse,
          }}
        >
          {saving ? 'Applying…' : 'Confirm and apply'}
        </button>
      </div>
    </div>
  );
}
