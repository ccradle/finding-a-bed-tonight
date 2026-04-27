/**
 * Platform-operator MFA enrollment page (F11 task 4.2 / spec
 * Requirement: MFA enrollment displays QR + manual secret + supported
 * authenticators).
 *
 * Reached after first-login (`mfa_enabled=false`) when the operator
 * holds an mfa-setup-scoped token. Flow:
 *   1. Mount → POST /auth/platform/mfa-setup → backend returns
 *      { secret, qrUri, backupCodes }
 *   2. Render QR + manual secret + supported-authenticators list
 *   3. Operator scans QR + types 6-digit TOTP code
 *   4. Submit → POST /auth/platform/mfa-confirm → backend returns post-MFA
 *      access token + (the same) 10 backup codes were already shown to the
 *      browser memory at step 1; we now display them via BackupCodesDisplay
 *   5. Operator confirms saving codes → navigate to /platform/dashboard
 *
 * Reload-mid-enrollment scenario: setup is idempotent server-side per
 * platform_user_setup_mfa contract — re-mounting calls /mfa-setup again
 * and gets the same secret + codes. If the mfa-setup token has expired,
 * the platformFetch wrapper's 401 handler kicks the operator back to
 * /platform/login.
 */

import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import QRCode from 'qrcode';
import { color } from '../../theme/colors';
import { usePlatformAuth } from '../../auth/PlatformAuthContext';
import { platformFetch } from './helpers/platformApi';
import { BackupCodesDisplay } from './components/BackupCodesDisplay';

interface SetupResponse {
  secret: string;
  qrUri: string;
  backupCodes: string[];
}

interface ConfirmResponse {
  token: string;
  expiresInSeconds: number;
}

type Phase = 'loading' | 'scan' | 'codes' | 'error';

const SUPPORTED_AUTHENTICATORS =
  'Works with Google Authenticator, Microsoft Authenticator, 1Password, Authy, Bitwarden, and any TOTP-compatible app.';

export default function PlatformMfaEnroll() {
  const navigate = useNavigate();
  const { login } = usePlatformAuth();
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const [phase, setPhase] = useState<Phase>('loading');
  const [secret, setSecret] = useState('');
  const [qrUri, setQrUri] = useState('');
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [totpCode, setTotpCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);

  // Step 1: fetch setup material on mount. Idempotent per backend
  // contract — re-mount returns the same secret if mfa not yet confirmed.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const response = await platformFetch('/api/v1/auth/platform/mfa-setup', {
          method: 'POST',
        });
        if (cancelled) return;
        if (response.status !== 200) {
          // 401 routed by platformFetch; this branch is the unexpected case.
          setPhase('error');
          setErrorMsg('Could not start MFA enrollment. Please sign in again.');
          return;
        }
        const body = (await response.json()) as SetupResponse;
        setSecret(body.secret);
        setQrUri(body.qrUri);
        setBackupCodes(body.backupCodes);
        setPhase('scan');
      } catch {
        if (!cancelled) {
          setPhase('error');
          setErrorMsg("Couldn't reach server. Check your connection and try again.");
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Render QR onto canvas whenever qrUri changes.
  useEffect(() => {
    if (canvasRef.current && qrUri) {
      QRCode.toCanvas(canvasRef.current, qrUri, { width: 200, margin: 2 });
    }
  }, [qrUri]);

  const handleConfirm = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const response = await platformFetch('/api/v1/auth/platform/mfa-confirm', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: totpCode }),
      });
      if (response.status !== 200) {
        setErrorMsg('Code invalid. Please try again with a fresh code from your app.');
        setTotpCode('');
        return;
      }
      const body = (await response.json()) as ConfirmResponse;
      login(body.token);
      // Now that MFA is enrolled, display backup codes (one-shot — they
      // were returned at /mfa-setup, kept in component state through
      // confirm). Operator confirms saving them, then we navigate.
      setPhase('codes');
    } catch {
      setErrorMsg("Couldn't reach server. Check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  };

  if (phase === 'loading') {
    return (
      <main style={mainStyle} role="status" aria-live="polite">
        <p>Loading enrollment…</p>
      </main>
    );
  }

  if (phase === 'error') {
    return (
      <main style={mainStyle}>
        <h1 style={{ marginTop: 0 }}>Enrollment unavailable</h1>
        <p style={{ color: color.error }}>{errorMsg}</p>
      </main>
    );
  }

  if (phase === 'codes') {
    return (
      <main style={mainStyle}>
        <h1 style={{ marginTop: 0 }}>Save your backup codes</h1>
        <BackupCodesDisplay
          codes={backupCodes}
          onContinue={() => navigate('/platform/dashboard', { replace: true })}
        />
      </main>
    );
  }

  // phase === 'scan'
  return (
    <main style={mainStyle}>
      <h1 style={{ marginTop: 0 }}>Set up sign-in verification</h1>
      <p style={{ color: color.textSecondary, lineHeight: 1.5 }}>
        Scan the code below with your authenticator app, then enter the
        6-digit code it generates to confirm.
      </p>
      <p style={{ fontSize: '0.875rem', color: color.textSecondary }}>
        {SUPPORTED_AUTHENTICATORS}
      </p>
      <div
        style={{
          padding: '1rem',
          backgroundColor: '#ffffff',
          borderRadius: '8px',
          border: `1px solid ${color.border}`,
          textAlign: 'center',
          margin: '1rem 0',
        }}
      >
        <canvas
          ref={canvasRef}
          role="img"
          aria-label="QR code containing your TOTP secret. If you cannot scan, use the manual code below."
          aria-describedby="platform-mfa-manual-secret"
          data-testid="platform-mfa-qr-canvas"
        />
      </div>
      <p style={{ fontSize: '0.875rem' }}>
        Can't scan? Enter this code manually in your authenticator app:
      </p>
      <code
        id="platform-mfa-manual-secret"
        data-testid="platform-mfa-manual-secret"
        style={{
          display: 'block',
          fontFamily: 'monospace',
          fontSize: '1.125rem',
          padding: '0.5rem',
          backgroundColor: color.bgTertiary,
          borderRadius: '4px',
          wordBreak: 'break-all',
          marginBottom: '1rem',
        }}
      >
        {secret}
      </code>
      <form onSubmit={handleConfirm} noValidate>
        <label style={{ display: 'block', marginBottom: '1rem' }}>
          <span style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
            6-digit code from your authenticator app
          </span>
          <input
            type="text"
            inputMode="numeric"
            pattern="[0-9]{6}"
            maxLength={6}
            placeholder="123456"
            autoComplete="one-time-code"
            value={totpCode}
            onChange={(e) => setTotpCode(e.target.value)}
            required
            data-testid="platform-mfa-confirm-input"
            style={{
              width: '100%',
              padding: '0.5rem',
              fontSize: '1.25rem',
              fontFamily: 'monospace',
              letterSpacing: '0.1em',
              borderRadius: '4px',
              border: `1px solid ${color.border}`,
              backgroundColor: color.bg,
              color: color.text,
            }}
          />
        </label>
        {errorMsg && (
          <div
            role="alert"
            data-testid="platform-mfa-confirm-error"
            style={{
              backgroundColor: color.errorBg,
              color: color.error,
              padding: '0.5rem 0.75rem',
              borderRadius: '4px',
              marginBottom: '1rem',
              fontSize: '0.875rem',
            }}
          >
            {errorMsg}
          </div>
        )}
        <button
          type="submit"
          disabled={submitting}
          data-testid="platform-mfa-confirm-submit"
          style={{
            width: '100%',
            padding: '0.75rem',
            fontSize: '1rem',
            fontWeight: 600,
            borderRadius: '4px',
            border: 'none',
            backgroundColor: submitting ? color.primaryDisabled : color.primary,
            color: color.textInverse,
            cursor: submitting ? 'not-allowed' : 'pointer',
          }}
        >
          {submitting ? 'Confirming…' : 'Confirm'}
        </button>
      </form>
    </main>
  );
}

const mainStyle: React.CSSProperties = {
  maxWidth: '480px',
  margin: '4rem auto',
  padding: '2rem',
  backgroundColor: color.bgSecondary,
  borderRadius: '8px',
  border: `1px solid ${color.border}`,
};
