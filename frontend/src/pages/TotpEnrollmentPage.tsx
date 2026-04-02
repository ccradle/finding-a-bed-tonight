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

/** Client-side QR code renderer — TOTP secret never leaves the browser */
function QrCodeCanvas({ uri, ...props }: { uri: string; 'data-testid'?: string }) {
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
      <canvas ref={canvasRef} style={{ display: 'block', margin: '0 auto' }} />
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
          <QrCodeCanvas data-testid="totp-qr-code" uri={qrUri} />

          {/* Manual entry secret */}
          <details style={{ marginBottom: 16 }}>
            <summary style={{ fontSize: text.xs, color: color.textMuted, cursor: 'pointer' }}>
              <FormattedMessage id="totp.manualEntry" />
            </summary>
            <code data-testid="totp-manual-secret" style={{
              display: 'block', padding: 12, marginTop: 8, borderRadius: 8,
              backgroundColor: color.bgTertiary, fontSize: text.sm, wordBreak: 'break-all',
              fontFamily: 'monospace', color: color.text,
            }}>
              {secret}
            </code>
          </details>

          {/* Verify code */}
          <label style={{ fontSize: text.xs, fontWeight: weight.semibold, color: color.textTertiary, display: 'block', marginBottom: 4 }}>
            <FormattedMessage id="totp.enterCode" />
          </label>
          <input
            data-testid="totp-verify-input"
            type="text"
            inputMode="numeric"
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
        <div>
          <div style={{
            padding: '16px', borderRadius: 12, backgroundColor: color.warningBg,
            color: color.warning, fontSize: text.sm, fontWeight: weight.semibold, marginBottom: 16,
          }}>
            <FormattedMessage id="totp.codesWarning" />
          </div>

          <div data-testid="backup-codes-grid" style={{
            display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 16,
          }}>
            {backupCodes.map((code, i) => (
              <div key={i} style={{
                padding: '8px 12px', borderRadius: 8, backgroundColor: color.bgTertiary,
                fontFamily: 'monospace', fontSize: text.sm, fontWeight: weight.bold,
                textAlign: 'center', color: color.text,
              }}>
                {code}
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
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
          </div>

          <p style={{ fontSize: text.xs, color: color.textMuted, marginBottom: 16 }}>
            <FormattedMessage id="totp.storageGuidance" />
          </p>

          <button
            data-testid="totp-done-button"
            onClick={() => navigate(-1)}
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
