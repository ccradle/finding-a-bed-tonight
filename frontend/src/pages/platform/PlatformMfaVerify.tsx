/**
 * Platform-operator MFA verification page (F11 task 4.6 / spec
 * Requirement: MFA verify error states).
 *
 * Reached after successful password login when MFA is already enabled.
 * Operator submits a 6-digit TOTP code OR an 8-char backup code; the
 * backend's PlatformAuthService.verifyMfa decides which is valid.
 *
 * Spec error states:
 *   - Wrong code (401 with attempts-remaining): "Code invalid. X attempts remaining…"
 *   - Lockout reached (5 fails / 15 min): "Too many failed attempts. Account locked for 15 minutes."
 *   - Network error: "Couldn't reach server…"
 *   - Backup code path mirrors TOTP error handling
 *
 * On success, the post-MFA access token is stored in sessionStorage and
 * the operator lands on /platform/dashboard.
 */

import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { color } from '../../theme/colors';
import { usePlatformAuth } from '../../auth/PlatformAuthContext';
import { platformFetch } from './helpers/platformApi';

interface VerifyResponse {
  token: string;
  expiresInSeconds: number;
}

interface BackendError {
  error?: string;
  message?: string;
  attemptsRemaining?: number;
}

type Mode = 'totp' | 'backup-code';

export default function PlatformMfaVerify() {
  const navigate = useNavigate();
  const { login } = usePlatformAuth();

  const [mode, setMode] = useState<Mode>('totp');
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [locked, setLocked] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting || locked) return;
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const response = await platformFetch('/api/v1/auth/platform/login/mfa-verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code }),
      });
      if (response.status === 200) {
        const body = (await response.json()) as VerifyResponse;
        login(body.token);
        navigate('/platform/dashboard', { replace: true });
        return;
      }
      // 401 paths: invalid code or lockout
      let parsed: BackendError = {};
      try {
        parsed = (await response.json()) as BackendError;
      } catch {
        // Body not JSON — treat as generic invalid
      }
      const errCode = parsed.error ?? '';
      if (errCode === 'account_locked' || errCode === 'invalid_credentials_locked') {
        setLocked(true);
        setErrorMsg(
          'Too many failed attempts. Account locked for 15 minutes. If you\'ve lost your phone, use a backup code.',
        );
      } else if (typeof parsed.attemptsRemaining === 'number') {
        const label = mode === 'totp' ? 'Code' : 'Backup code';
        setErrorMsg(
          `${label} invalid. ${parsed.attemptsRemaining} attempts remaining before lockout.`,
        );
      } else {
        const label = mode === 'totp' ? 'Code' : 'Backup code';
        setErrorMsg(`${label} invalid. Please try again.`);
      }
      setCode('');
    } catch {
      setErrorMsg("Couldn't reach server. Check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  };

  const totpInputProps = {
    inputMode: 'numeric' as const,
    pattern: '[0-9]{6}',
    maxLength: 6,
    placeholder: '123456',
    autoComplete: 'one-time-code',
  };
  const backupInputProps = {
    inputMode: 'text' as const,
    maxLength: 12,
    placeholder: 'XXXX-XXXX',
    autoComplete: 'one-time-code',
  };
  const inputProps = mode === 'totp' ? totpInputProps : backupInputProps;

  return (
    <main
      style={{
        maxWidth: '420px',
        margin: '4rem auto',
        padding: '2rem',
        backgroundColor: color.bgSecondary,
        borderRadius: '8px',
        border: `1px solid ${color.border}`,
      }}
      data-testid="platform-mfa-verify-main"
    >
      <h1 style={{ marginTop: 0, fontSize: '1.5rem' }}>Verify your identity</h1>
      <p style={{ color: color.textSecondary, fontSize: '0.875rem', lineHeight: 1.5 }}>
        {mode === 'totp'
          ? 'Open your authenticator app and enter the 6-digit code.'
          : 'Enter one of your single-use backup codes.'}
      </p>
      <form onSubmit={handleSubmit} noValidate>
        <label style={{ display: 'block', marginBottom: '1rem' }}>
          <span style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
            {mode === 'totp' ? 'Authenticator code' : 'Backup code'}
          </span>
          <input
            {...inputProps}
            type="text"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            required
            autoFocus
            disabled={locked && mode === 'totp'}
            data-testid={
              mode === 'totp'
                ? 'platform-mfa-totp-input'
                : 'platform-mfa-backup-input'
            }
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
              opacity: locked && mode === 'totp' ? 0.5 : 1,
            }}
          />
        </label>
        {errorMsg && (
          <div
            role="alert"
            data-testid="platform-mfa-error"
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
          data-testid="platform-mfa-submit"
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
          {submitting ? 'Verifying…' : 'Verify'}
        </button>
      </form>
      <p style={{ marginTop: '1rem', fontSize: '0.875rem' }}>
        {mode === 'totp' ? (
          <button
            type="button"
            onClick={() => {
              setMode('backup-code');
              setCode('');
              setErrorMsg(null);
            }}
            data-testid="platform-mfa-use-backup-code"
            style={{
              background: 'none',
              border: 'none',
              padding: 0,
              color: color.primaryText,
              cursor: 'pointer',
              textDecoration: 'underline',
              fontSize: 'inherit',
            }}
          >
            Use backup code instead
          </button>
        ) : (
          <button
            type="button"
            onClick={() => {
              setMode('totp');
              setCode('');
              setErrorMsg(null);
            }}
            data-testid="platform-mfa-use-totp"
            style={{
              background: 'none',
              border: 'none',
              padding: 0,
              color: color.primaryText,
              cursor: 'pointer',
              textDecoration: 'underline',
              fontSize: 'inherit',
            }}
          >
            Use authenticator code instead
          </button>
        )}
      </p>
    </main>
  );
}
