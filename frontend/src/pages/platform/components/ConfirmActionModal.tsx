/**
 * Three-variant confirmation modal for sensitive operator actions
 * (F11 task 4.5 / spec Requirements: Print backup codes / Copy backup
 * codes / Destructive action confirmation).
 *
 * Variants:
 *   - 'print'       — button labels "Cancel" / "Print Anyway"
 *   - 'copy'        — button labels "Cancel" / "Copy Anyway"
 *   - 'destructive' — typed-confirmation field; button labels
 *                     "Cancel" / actionLabel (e.g. "Suspend Tenant")
 *
 * Cancel is always default-focused per Marcus condition #2 / spec.
 * Modal is render-prop-style: parent owns `open` state and `onConfirm`
 * callback; modal manages typed-confirmation match locally.
 */

import { useEffect, useId, useRef, useState } from 'react';
import { color } from '../../../theme/colors';

export type Variant =
  | { kind: 'print' }
  | { kind: 'copy' }
  | { kind: 'destructive'; expectedSlug: string; actionLabel: string };

/**
 * Round 9 #7 — extracted predicate so the empty-slug refusal contract
 * can be unit-tested without rendering JSX (no RTL/jsdom in this tree).
 * Returns true iff the modal must early-return rather than mount.
 *
 * The contract: a destructive variant with a falsy `expectedSlug` would
 * make the typed-confirm match logic vacuous (`'' === ''` enables the
 * confirm button on first paint), so the modal refuses to render. The
 * caller is misconfigured; surfacing as a no-render + console.error is
 * loud enough to catch in dev without leaking destructive UX in prod.
 */
export function shouldRefuseDestructiveRender(variant: Variant): boolean {
  return variant.kind === 'destructive' && !variant.expectedSlug;
}

interface Props {
  open: boolean;
  variant: Variant;
  /** Heading + body copy per variant. The component supplies sensible
   *  default copy; override only if a specific call site needs different
   *  wording (e.g. a "Hard delete" button could use the destructive
   *  variant with different body text). */
  heading?: string;
  body?: string;
  onCancel: () => void;
  onConfirm: () => void;
}

const PRINT_BODY =
  'These codes will be sent to your printer or saved as a PDF. They will appear in your OS print queue and may be retained by network printers. Continue?';
const COPY_BODY =
  'These codes will be placed on your system clipboard. Clipboard managers and pasted-into apps may retain them. The clipboard will auto-clear in 30 seconds. Continue?';

function defaultBody(variant: Variant): string {
  switch (variant.kind) {
    case 'print':
      return PRINT_BODY;
    case 'copy':
      return COPY_BODY;
    case 'destructive':
      // Round 7 M8: prior versions interpolated `expectedSlug` even when
      // empty, producing the literal copy `Type the tenant slug "" to
      // confirm.` which is both confusing and (with the typed-slug
      // match logic) rendered the typed-confirm a no-op (`'' === ''`).
      // Defensively coerce missing-slug to a body that does not promise
      // a typed-confirm flow.
      if (!variant.expectedSlug) {
        return 'This action cannot be undone.';
      }
      return `Type the tenant slug "${variant.expectedSlug}" to confirm. This action cannot be undone.`;
  }
}

function defaultHeading(variant: Variant): string {
  switch (variant.kind) {
    case 'print':
      return 'Print backup codes?';
    case 'copy':
      return 'Copy backup codes to clipboard?';
    case 'destructive':
      return 'Confirm action';
  }
}

function actionLabel(variant: Variant): string {
  switch (variant.kind) {
    case 'print':
      return 'Print Anyway';
    case 'copy':
      return 'Copy Anyway';
    case 'destructive':
      return variant.actionLabel;
  }
}

export function ConfirmActionModal({
  open,
  variant,
  heading,
  body,
  onCancel,
  onConfirm,
}: Props) {
  const cancelRef = useRef<HTMLButtonElement>(null);
  const slugInputId = useId();
  const [typedSlug, setTypedSlug] = useState('');

  // Default-focus Cancel + reset typed slug whenever the modal opens.
  useEffect(() => {
    if (open) {
      setTypedSlug('');
      // Defer to next microtask so the modal is mounted before focus.
      Promise.resolve().then(() => cancelRef.current?.focus());
    }
  }, [open]);

  if (!open) return null;

  // Round 8 N3 / round 9 #7 — refuse to render a destructive modal
  // with an empty expectedSlug. The typed-slug match logic
  // (`'' === ''`) would otherwise enable the confirm button on first
  // paint with no input. Surface a runtime error so the
  // misconfiguration is loud, not silently bypassable. Belt-and-
  // braces against future regressions: the dashboard-side fix
  // removed the only known broken caller (round 7 CRITICAL #1), this
  // is the component-side fix that prevents a new caller from
  // re-introducing the bug. Logic extracted to
  // {@link shouldRefuseDestructiveRender} so vitest can pin the
  // contract without RTL.
  if (shouldRefuseDestructiveRender(variant)) {
    if (typeof console !== 'undefined') {
      console.error(
        'ConfirmActionModal: destructive variant requires a non-empty expectedSlug',
      );
    }
    return null;
  }

  const slugMatches =
    variant.kind !== 'destructive' || typedSlug === variant.expectedSlug;
  const confirmDisabled = !slugMatches;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby={`${slugInputId}-heading`}
      data-testid="platform-confirm-modal"
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0,0,0,0.5)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
      }}
    >
      <div
        style={{
          backgroundColor: color.bg,
          color: color.text,
          maxWidth: '480px',
          width: '90%',
          padding: '1.5rem',
          borderRadius: '8px',
          border: `1px solid ${color.border}`,
        }}
      >
        <h2
          id={`${slugInputId}-heading`}
          style={{ marginTop: 0, fontSize: '1.125rem' }}
        >
          {heading ?? defaultHeading(variant)}
        </h2>
        <p style={{ color: color.textSecondary, lineHeight: 1.5 }}>
          {body ?? defaultBody(variant)}
        </p>
        {variant.kind === 'destructive' && (
          <label style={{ display: 'block', marginTop: '0.75rem' }}>
            <span style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
              Tenant slug
            </span>
            <input
              id={slugInputId}
              type="text"
              value={typedSlug}
              onChange={(e) => setTypedSlug(e.target.value)}
              data-testid="platform-confirm-slug-input"
              style={{
                width: '100%',
                padding: '0.5rem',
                fontSize: '1rem',
                borderRadius: '4px',
                border: `1px solid ${color.border}`,
                fontFamily: 'monospace',
              }}
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
            />
          </label>
        )}
        <div style={{ marginTop: '1.25rem', display: 'flex', justifyContent: 'flex-end', gap: '0.5rem' }}>
          <button
            ref={cancelRef}
            type="button"
            onClick={onCancel}
            data-testid="platform-confirm-cancel"
            style={{
              padding: '0.5rem 1rem',
              borderRadius: '4px',
              border: `1px solid ${color.border}`,
              backgroundColor: color.bg,
              color: color.text,
              cursor: 'pointer',
              fontWeight: 600,
            }}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={confirmDisabled}
            data-testid="platform-confirm-action"
            style={{
              padding: '0.5rem 1rem',
              borderRadius: '4px',
              border: 'none',
              backgroundColor: confirmDisabled ? color.primaryDisabled : color.platform,
              color: color.textInverse,
              cursor: confirmDisabled ? 'not-allowed' : 'pointer',
              fontWeight: 600,
            }}
          >
            {actionLabel(variant)}
          </button>
        </div>
      </div>
    </div>
  );
}
