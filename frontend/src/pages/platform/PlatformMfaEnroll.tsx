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
import { PLATFORM_OPERATOR_USER_GUIDE_URL } from './constants';

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
  const { login, logout } = usePlatformAuth();
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const [phase, setPhase] = useState<Phase>('loading');
  const [secret, setSecret] = useState('');
  const [qrUri, setQrUri] = useState('');
  const [backupCodes, setBackupCodes] = useState<string[]>([]);
  const [totpCode, setTotpCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  // Wave 3 fix — hold the post-MFA access token in component state
  // through the codes phase. We CANNOT call login() here because that
  // would swap the sessionStorage JWT from mfa-setup-scoped to
  // post-MFA-access-scoped, which causes PlatformProtectedRoute on
  // /mfa-enroll (requiredScope='mfa-setup') to re-evaluate and
  // navigate the operator away — they would never see their backup
  // codes. login() runs in handleCodesContinue when the operator
  // clicks Continue.
  const [pendingAccessToken, setPendingAccessToken] = useState<string | null>(null);

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
          // Round 8 Bug A + round 9 #1: platformFetch no longer
          // auto-redirects on 401 from auth-flow paths (the page used
          // to lean on that for the scoped-token-expired case).
          // Inspect the response body so the operator gets routed to
          // /login when their mfa-setup token is genuinely dead,
          // rather than stuck on a dead-end "enrollment unavailable"
          // page with no escape.
          if (response.status === 401) {
            let parsed: { error?: string } = {};
            try { parsed = (await response.json()) as { error?: string }; } catch {
              /* body not JSON — fall through to dead-end UX */
            }
            if (parsed.error === 'invalid_platform_token') {
              sessionStorage.setItem('fabt.platform.toast.session-expired', 'true');
              logout();
              navigate('/platform/login', { replace: true });
              return;
            }
          }
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
  }, [logout, navigate]);

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
        // Same scoped-token-expired escape as the /mfa-setup branch
        // above. /mfa-confirm 401 with `invalid_platform_token`
        // means the mfa-setup token died between enroll and confirm
        // (10-min window) — kick to login with the toast rather than
        // looping the operator on "Code invalid."
        if (response.status === 401) {
          let parsed: { error?: string } = {};
          try { parsed = (await response.json()) as { error?: string }; } catch {
            /* body not JSON */
          }
          if (parsed.error === 'invalid_platform_token') {
            sessionStorage.setItem('fabt.platform.toast.session-expired', 'true');
            logout();
            navigate('/platform/login', { replace: true });
            return;
          }
        }
        setErrorMsg('Code invalid. Please try again with a fresh code from your app.');
        setTotpCode('');
        return;
      }
      const body = (await response.json()) as ConfirmResponse;
      // J3: validate token field is present + non-empty. Without this
      // check we'd write the literal "undefined" to sessionStorage on a
      // backend partial-migration / mock-server bug.
      if (typeof body.token !== 'string' || body.token.length === 0) {
        setErrorMsg('Server returned an unexpected response. Please try signing in again.');
        return;
      }
      // Wave 3 fix — defer login() until the operator clicks Continue
      // on the codes display. See pendingAccessToken state declaration
      // above for the route-guard race rationale. login() runs in
      // handleCodesContinue.
      setPendingAccessToken(body.token);
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
          onContinue={() => {
            // Wave 3 fix — promote the deferred post-MFA token to
            // sessionStorage NOW (not at /mfa-confirm time) so the
            // route guard kept seeing an mfa-setup-scoped JWT
            // throughout the codes display. Without this defer the
            // operator would be navigated away before they could
            // save their codes. The `if` guard is defensive — under
            // normal flow `pendingAccessToken` IS set when the
            // codes phase mounts (see handleConfirm). If somehow
            // null, fall back to navigating to /platform/login so
            // the operator re-authenticates rather than landing on
            // a dashboard with no usable session.
            if (pendingAccessToken) {
              login(pendingAccessToken);
              navigate('/platform/dashboard', { replace: true });
            } else {
              navigate('/platform/login', { replace: true });
            }
          }}
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
      {/* §7.3 — first-time help link to the user guide. URL pinned in
          constants.ts so a future doc-tree move only needs one edit. */}
      <p style={{ fontSize: '0.875rem', color: color.textSecondary }}>
        First time enrolling? See the{' '}
        <a
          href={PLATFORM_OPERATOR_USER_GUIDE_URL}
          target="_blank"
          rel="noopener noreferrer"
          data-testid="platform-mfa-enroll-user-guide-link"
          style={{ color: color.primaryText }}
        >
          Platform Operator User Guide
        </a>
        .
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
