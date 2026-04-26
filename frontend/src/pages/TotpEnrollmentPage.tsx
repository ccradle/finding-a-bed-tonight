import { useState, useEffect, useRef } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { api } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

/**
 * TOTP enrollment page — "Enable Sign-In Verification"
 *
 * Flow: generate QR → scan with authenticator → enter code → confirm → show backup codes
 * User-facing language: "sign-in verification" not "2FA" (D15)
 */

interface EnrollmentResponse {
  qrUri: string;
  secret: string;
}

interface ConfirmResponse {
  enabled: boolean;
  backupCodes: string[];
}

/**
 * Client-side QR code renderer — TOTP secret never leaves the browser.
 *
 * §6.13 a11y: <canvas> is opaque to screen readers, so we use role="img"
 * + aria-label to give assistive tech an equivalent description. The
 * adjacent visible secret + copy button (rendered by the parent) are the
 * keyboard-accessible alternative for users who cannot scan.
 */
function QrCodeCanvas({ uri, ariaLabel, ...props }: {
  uri: string;
  ariaLabel: string;
  'data-testid'?: string;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    if (canvasRef.current && uri) {
      QRCode.toCanvas(canvasRef.current, uri, { width: 200, margin: 2 });
    }
  }, [uri]);

  return (
    <div {...props} style={{
      padding: 16, backgroundColor: '#ffffff', borderRadius: 12,
      border: `2px solid ${color.border}`, textAlign: 'center', marginBottom: 16,
    }}>
      <canvas
        ref={canvasRef}
        role="img"
        aria-label={ariaLabel}
        style={{ display: 'block', margin: '0 auto' }}
      />
    </div>
  );
}

export function TotpEnrollmentPage() {
  const intl = useIntl();
  const navigate = useNavigate();

  const [step, setStep] = useState<'start' | 'scan' | 'codes' | 'error'>('start');
  const [qrUri, setQrUri] = useState('');
  const [secret, setSecret] = useState('');
  const [verifyCode, setVerifyCode] = useState('');
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const startEnrollment = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.post<EnrollmentResponse>('/api/v1/auth/enroll-totp', {});
      setQrUri(data.qrUri);
      setSecret(data.secret);
      setStep('scan');
    } catch {
      setError(intl.formatMessage({ id: 'totp.enrollError' }));
      setStep('error');
    } finally {
      setLoading(false);
    }
  };

  const confirmEnrollment = async () => {
    if (verifyCode.length !== 6) return;
    setLoading(true);
    setError(null);
    try {
      const data = await api.post<ConfirmResponse>('/api/v1/auth/confirm-totp-enrollment', { code: verifyCode });
      setBackupCodes(data.backupCodes);
      setStep('codes');
    } catch {
      setError(intl.formatMessage({ id: 'totp.invalidCode' }));
    } finally {
      setLoading(false);
    }
  };

  const copyAllCodes = () => {
    navigator.clipboard.writeText(backupCodes.join('\n'));
  };

  const downloadCodes = () => {
    const blob = new Blob([
      'Finding A Bed Tonight — Backup Codes\n',
      '=====================================\n',
      'Store these codes in a safe place.\n',
      'Each code can only be used once.\n\n',
      ...backupCodes.map((c, i) => `${i + 1}. ${c}\n`),
    ], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'fabt-backup-codes.txt';
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div style={{ maxWidth: 500, margin: '0 auto', padding: 24 }}>
      <h2 style={{ fontSize: text.xl, fontWeight: weight.bold, color: color.text, marginBottom: 16 }}>
        <FormattedMessage id="totp.enrollTitle" />
      </h2>

      {error && (
        <div style={{ padding: '12px 16px', borderRadius: 8, backgroundColor: color.errorBg, color: color.error, fontSize: text.sm, marginBottom: 16 }}>
          {error}
        </div>
      )}

      {step === 'start' && (
        <div>
          <p style={{ fontSize: text.base, color: color.textTertiary, marginBottom: 16 }}>
            <FormattedMessage id="totp.enrollDescription" />
          </p>
          <button
            data-testid="enable-totp-button"
            onClick={startEnrollment}
            disabled={loading}
            style={{
              padding: '12px 24px', borderRadius: 10, border: 'none',
              backgroundColor: color.primary, color: color.textInverse,
              fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer',
              minHeight: 44,
            }}
          >
            {loading ? '...' : intl.formatMessage({ id: 'totp.enableButton' })}
          </button>
        </div>
      )}

      {step === 'scan' && (
        <div>
          <p style={{ fontSize: text.sm, color: color.textTertiary, marginBottom: 12 }}>
            <FormattedMessage id="totp.scanInstructions" />
          </p>

          {/* QR Code — rendered locally, TOTP secret never leaves the browser */}
          <QrCodeCanvas
            data-testid="totp-qr-code"
            uri={qrUri}
            ariaLabel={intl.formatMessage({ id: 'totp.qrAriaLabel' })}
          />

          {/* Manual entry secret — §6.13: <details>/<summary> is the
              native keyboard-accessible disclosure for users who can't
              scan. Copy button has explicit aria-label so screen readers
              announce purpose, not just the icon glyph. */}
          <details style={{ marginBottom: 16 }}>
            <summary style={{ fontSize: text.xs, color: color.textMuted, cursor: 'pointer' }}>
              <FormattedMessage id="totp.manualEntry" />
            </summary>
            <div style={{ display: 'flex', alignItems: 'stretch', gap: 8, marginTop: 8 }}>
              <code data-testid="totp-manual-secret" style={{
                flex: 1, padding: 12, borderRadius: 8,
                backgroundColor: color.bgTertiary, fontSize: text.sm, wordBreak: 'break-all',
                fontFamily: 'monospace', color: color.text,
              }}>
                {secret}
              </code>
              <button
                type="button"
                data-testid="totp-copy-secret-button"
                onClick={() => navigator.clipboard.writeText(secret)}
                aria-label={intl.formatMessage({ id: 'totp.copySecretAriaLabel' })}
                style={{
                  padding: '0 16px', borderRadius: 8, border: `2px solid ${color.border}`,
                  backgroundColor: color.bg, color: color.text, fontSize: text.sm,
                  fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
                  whiteSpace: 'nowrap',
                }}
              >
                <FormattedMessage id="totp.copySecret" />
              </button>
            </div>
          </details>

          {/* Verify code — §6.12 a11y semantics: type=text + inputMode=numeric
              gives mobile users the numeric keypad without losing copy/paste,
              autoComplete=one-time-code lets browsers auto-fill from SMS/email,
              pattern enforces 6 digits at the constraint-validation layer,
              aria-label gives screen readers a description that survives even
              when the visual <label> is far away or styled away. */}
          <label htmlFor="totp-verify-input" style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
            <FormattedMessage id="totp.enterCode" />
          </label>
          <input
            id="totp-verify-input"
            data-testid="totp-verify-input"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            pattern="[0-9]{6}"
            aria-label={intl.formatMessage({ id: 'totp.codeAriaLabel' })}
            maxLength={6}
            value={verifyCode}
            onChange={(e) => {
              const v = e.target.value.replace(/\D/g, '');
              setVerifyCode(v);
            }}
            autoFocus
            placeholder="000000"
            style={{
              width: '100%', padding: 12, borderRadius: 8, border: `2px solid ${color.border}`,
              fontSize: text.xl, fontFamily: 'monospace', textAlign: 'center', letterSpacing: '0.3em',
              marginBottom: 16,
            }}
          />

          <button
            data-testid="totp-verify-submit"
            onClick={confirmEnrollment}
            disabled={loading || verifyCode.length !== 6}
            style={{
              width: '100%', padding: 12, borderRadius: 10, border: 'none',
              backgroundColor: verifyCode.length === 6 ? color.primary : color.borderMedium,
              color: color.textInverse, fontSize: text.base, fontWeight: weight.bold,
              cursor: verifyCode.length === 6 ? 'pointer' : 'default', minHeight: 44,
            }}
          >
            {loading ? '...' : intl.formatMessage({ id: 'totp.verifyButton' })}
          </button>
        </div>
      )}

      {step === 'codes' && (
        <div className="fabt-backup-codes-step">
          {/* §6.14: <h2> heading anchors the section for screen-reader nav
              and document outline. The "Save these codes" wording cues the
              user that this content is high-stakes (they cannot retrieve
              the codes again later). */}
          <h2 style={{
            fontSize: text.lg, fontWeight: weight.bold, color: color.text,
            marginTop: 0, marginBottom: 8,
          }}>
            <FormattedMessage id="totp.codesHeading" />
          </h2>

          <div role="alert" style={{
            padding: '16px', borderRadius: 12, backgroundColor: color.warningBg,
            color: color.warning, fontSize: text.sm, fontWeight: weight.semibold, marginBottom: 16,
          }}>
            <FormattedMessage id="totp.codesWarning" />
          </div>

          {/* §6.14: <ol> is the right semantic for an ordered list of
              one-time codes; screen readers announce "1 of N" so the user
              knows which code they're hearing. Per-code copy button gives
              keyboard-only users a direct copy path without selecting
              text. The inner code styling is large enough to read at
              arm's length on a printout (~16pt monospace). */}
          <ol
            data-testid="backup-codes-list"
            aria-label={intl.formatMessage({ id: 'totp.codesListAriaLabel' })}
            style={{
              listStyle: 'decimal inside',
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: 8,
              padding: 0,
              margin: 0,
              marginBottom: 16,
            }}
          >
            {backupCodes.map((code, i) => (
              <li
                key={i}
                style={{
                  padding: '8px 12px', borderRadius: 8, backgroundColor: color.bgTertiary,
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  gap: 8,
                }}
              >
                <code style={{
                  fontFamily: 'monospace', fontSize: text.base, fontWeight: weight.bold,
                  color: color.text, letterSpacing: '0.05em',
                }}>
                  {code}
                </code>
                <button
                  type="button"
                  data-testid={`copy-code-${i}`}
                  onClick={() => navigator.clipboard.writeText(code)}
                  aria-label={intl.formatMessage(
                    { id: 'totp.copyCodeAriaLabel' },
                    { index: i + 1 },
                  )}
                  className="fabt-print-hide"
                  style={{
                    padding: '4px 10px', borderRadius: 6, border: `1px solid ${color.border}`,
                    backgroundColor: color.bg, color: color.text, fontSize: text.xs,
                    fontWeight: weight.semibold, cursor: 'pointer', minHeight: 28,
                  }}
                >
                  <FormattedMessage id="totp.copyCodeShort" />
                </button>
              </li>
            ))}
          </ol>

          <div className="fabt-print-hide" style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
            <button
              data-testid="copy-codes-button"
              onClick={copyAllCodes}
              style={{
                flex: 1, padding: 10, borderRadius: 8, border: `2px solid ${color.border}`,
                backgroundColor: color.bg, color: color.text, fontSize: text.sm,
                fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
              }}
            >
              <FormattedMessage id="totp.copyCodes" />
            </button>
            <button
              data-testid="download-codes-button"
              onClick={downloadCodes}
              style={{
                flex: 1, padding: 10, borderRadius: 8, border: `2px solid ${color.border}`,
                backgroundColor: color.bg, color: color.text, fontSize: text.sm,
                fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
              }}
            >
              <FormattedMessage id="totp.downloadCodes" />
            </button>
            <button
              data-testid="print-codes-button"
              onClick={() => window.print()}
              style={{
                flex: 1, padding: 10, borderRadius: 8, border: `2px solid ${color.border}`,
                backgroundColor: color.bg, color: color.text, fontSize: text.sm,
                fontWeight: weight.semibold, cursor: 'pointer', minHeight: 44,
              }}
            >
              <FormattedMessage id="totp.printCodes" />
            </button>
          </div>

          <p className="fabt-print-hide" style={{ fontSize: text.xs, color: color.textMuted, marginBottom: 16 }}>
            <FormattedMessage id="totp.storageGuidance" />
          </p>

          <button
            data-testid="totp-done-button"
            onClick={() => navigate(-1)}
            className="fabt-print-hide"
            style={{
              width: '100%', padding: 12, borderRadius: 10, border: 'none',
              backgroundColor: color.successBright, color: color.textInverse,
              fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer', minHeight: 44,
            }}
          >
            <FormattedMessage id="totp.done" />
          </button>
        </div>
      )}

      {step === 'error' && (
        <div>
          <button onClick={() => setStep('start')} style={{
            padding: '12px 24px', borderRadius: 10, border: `2px solid ${color.border}`,
            backgroundColor: color.bg, color: color.text, fontSize: text.base,
            cursor: 'pointer', minHeight: 44,
          }}>
            <FormattedMessage id="totp.tryAgain" />
          </button>
        </div>
      )}
    </div>
  );
}
