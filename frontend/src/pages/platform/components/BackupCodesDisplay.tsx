/* eslint-disable react/no-danger -- This file explicitly forbids dangerouslySetInnerHTML
   per warroom round 3 BLOCKER M1; lint rule check is here as a regression guard. */
/**
 * One-shot backup-codes display (F11 task 4.3 / spec Requirement: Backup
 * codes one-shot display with confirmation gate).
 *
 * Codes render via React text-node interpolation only (NEVER
 * dangerouslySetInnerHTML — Marcus condition + ESLint rule above).
 * Continue button is disabled until the operator checks "I have saved
 * my backup codes." Print + Copy each go through ConfirmActionModal.
 *
 * After Continue, the parent transitions away (typically to dashboard);
 * the Cache-Control: no-store header on /auth/platform/mfa-confirm (set
 * by the backend) prevents the browser back-button from re-rendering
 * the codes via cached response.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { color } from '../../../theme/colors';
import { ConfirmActionModal } from './ConfirmActionModal';
import { PrintFriendlyCodes } from './PrintFriendlyCodes';

interface Props {
  codes: string[];
  /** Called when the operator clicks Continue (after the confirmation
   *  checkbox is checked). Parent then navigates away. */
  onContinue: () => void;
}

const COPY_AUTO_CLEAR_MS = 30_000;

export function BackupCodesDisplay({ codes, onContinue }: Props) {
  const [confirmed, setConfirmed] = useState(false);
  const [printOpen, setPrintOpen] = useState(false);
  const [copyOpen, setCopyOpen] = useState(false);
  const [copyToast, setCopyToast] = useState(false);
  // M3: surface clipboard-write failures as an inline error instead of
  // silently swallowing — operator clicks Copy, sees nothing, gives up.
  const [copyError, setCopyError] = useState<string | null>(null);
  // M2/S1: track the auto-clear timer ID so a second Copy click cancels
  // the first timer (otherwise the first 30s timer would fire later and
  // wipe whatever the operator copied next — password manager paste,
  // different password, etc.). Also cleared on unmount.
  const clearTimerRef = useRef<number | null>(null);

  // Print-friendly markup is conditionally added to the DOM only while
  // print is invoked, then removed. Done here (not as a sibling component)
  // because window.print() captures the *current* DOM.
  const [readyToPrint, setReadyToPrint] = useState(false);
  useEffect(() => {
    if (!readyToPrint) return;
    // M4 (Safari async print): use the `afterprint` event to know when
    // to tear down the print-friendly DOM. window.print() is synchronous
    // on Chrome/Firefox but asynchronous on Safari (returns immediately
    // while the system dialog is still open). Pre-fix: we removed the
    // print DOM right after window.print() returned, which on Safari
    // happened BEFORE the spooler captured.
    const onAfterPrint = () => setReadyToPrint(false);
    window.addEventListener('afterprint', onAfterPrint);
    // Defer one frame so the print-friendly DOM mounts + the @media
    // print stylesheet attaches before window.print() captures.
    const id = window.setTimeout(() => {
      window.print();
    }, 50);
    return () => {
      window.clearTimeout(id);
      window.removeEventListener('afterprint', onAfterPrint);
    };
  }, [readyToPrint]);

  // M2/S1: cancel any in-flight clipboard-clear timer on unmount.
  useEffect(() => {
    return () => {
      if (clearTimerRef.current !== null) {
        window.clearTimeout(clearTimerRef.current);
      }
    };
  }, []);

  const onPrintConfirm = () => {
    setPrintOpen(false);
    setReadyToPrint(true);
  };

  const onCopyConfirm = useCallback(async () => {
    setCopyOpen(false);
    setCopyError(null);
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      setCopyToast(true);
      // M2/S1: cancel any prior auto-clear timer before scheduling a
      // new one. Without this guard, a re-copy at t=15s would leave
      // BOTH timers active; T1 fires at t=30s and wipes whatever the
      // operator pasted (their password-manager-saved codes, or some
      // other secret they copied in the interim).
      if (clearTimerRef.current !== null) {
        window.clearTimeout(clearTimerRef.current);
      }
      clearTimerRef.current = window.setTimeout(() => {
        navigator.clipboard.writeText('').catch(() => {
          /* ignore — denied permission is a no-op */
        });
        setCopyToast(false);
        clearTimerRef.current = null;
      }, COPY_AUTO_CLEAR_MS);
    } catch {
      // M3: surface failure inline so the operator knows to try Print
      // or hand-copy. Acceptable in dev (clipboard requires HTTPS or
      // localhost), real surface is a permission denied in prod.
      setCopyError(
        'Copy failed — your browser denied clipboard access. Use Print, or copy each code by hand.',
      );
    }
  }, [codes]);

  return (
    <>
      <div
        data-testid="platform-backup-codes-display"
        // The screen view is hidden during the brief print window so
        // the only thing the printer/PDF sees is PrintFriendlyCodes.
        style={readyToPrint ? { display: 'none' } : undefined}
      >
        <p
          style={{
            backgroundColor: color.warningBg,
            color: color.warning,
            padding: '0.75rem',
            borderRadius: '4px',
            fontWeight: 600,
            lineHeight: 1.5,
          }}
        >
          These 10 codes are your ONLY way back in if you lose your phone. Save
          them in a password manager NOW. They will never be shown again.
        </p>
        <ol
          data-testid="platform-backup-codes-list"
          style={{
            backgroundColor: color.bgTertiary,
            border: `1px solid ${color.border}`,
            borderRadius: '4px',
            padding: '1rem 1rem 1rem 2.5rem',
            fontFamily: 'monospace',
            fontSize: '1rem',
            lineHeight: 1.8,
            margin: '1rem 0',
          }}
        >
          {/* Text-node interpolation only — never dangerouslySetInnerHTML.
              Even a `<script>` in a code string would render literally. */}
          {codes.map((code, i) => (
            <li key={i} data-testid={`platform-backup-code-${i}`}>
              {code}
            </li>
          ))}
        </ol>
        <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
          <button
            type="button"
            onClick={() => setPrintOpen(true)}
            data-testid="platform-backup-codes-print"
            style={buttonStyle}
          >
            Print
          </button>
          <button
            type="button"
            onClick={() => setCopyOpen(true)}
            data-testid="platform-backup-codes-copy"
            style={buttonStyle}
          >
            Copy to clipboard
          </button>
        </div>
        {copyToast && (
          <div
            role="status"
            aria-live="polite"
            data-testid="platform-backup-codes-copy-toast"
            style={{
              marginTop: '0.75rem',
              backgroundColor: color.successBg,
              color: color.success,
              padding: '0.5rem 0.75rem',
              borderRadius: '4px',
              fontSize: '0.875rem',
            }}
          >
            Codes copied — clipboard will auto-clear in 30 seconds.
          </div>
        )}
        {copyError && (
          <div
            role="alert"
            data-testid="platform-backup-codes-copy-error"
            style={{
              marginTop: '0.75rem',
              backgroundColor: color.errorBg,
              color: color.error,
              padding: '0.5rem 0.75rem',
              borderRadius: '4px',
              fontSize: '0.875rem',
            }}
          >
            {copyError}
          </div>
        )}
        <label
          style={{
            display: 'block',
            marginTop: '1.5rem',
            padding: '0.75rem',
            backgroundColor: color.bgSecondary,
            borderRadius: '4px',
          }}
        >
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
            data-testid="platform-backup-codes-confirm-checkbox"
            style={{ marginRight: '0.5rem' }}
          />
          I have saved my backup codes
        </label>
        <button
          type="button"
          onClick={onContinue}
          disabled={!confirmed}
          data-testid="platform-backup-codes-continue"
          style={{
            marginTop: '1rem',
            padding: '0.75rem 1.5rem',
            fontSize: '1rem',
            fontWeight: 600,
            borderRadius: '4px',
            border: 'none',
            backgroundColor: confirmed ? color.primary : color.primaryDisabled,
            color: color.textInverse,
            cursor: confirmed ? 'pointer' : 'not-allowed',
          }}
        >
          Continue
        </button>
      </div>

      {readyToPrint && <PrintFriendlyCodes codes={codes} />}

      <ConfirmActionModal
        open={printOpen}
        variant={{ kind: 'print' }}
        onCancel={() => setPrintOpen(false)}
        onConfirm={onPrintConfirm}
      />
      <ConfirmActionModal
        open={copyOpen}
        variant={{ kind: 'copy' }}
        onCancel={() => setCopyOpen(false)}
        onConfirm={onCopyConfirm}
      />
    </>
  );
}

const buttonStyle: React.CSSProperties = {
  padding: '0.5rem 1rem',
  borderRadius: '4px',
  border: `1px solid ${color.border}`,
  backgroundColor: color.bg,
  color: color.text,
  cursor: 'pointer',
  fontWeight: 600,
};
