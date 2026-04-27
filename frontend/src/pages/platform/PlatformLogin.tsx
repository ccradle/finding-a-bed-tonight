/**
 * Platform-operator sign-in page (F11 task 4.1 / spec Requirement:
 * Platform operator sign-in page).
 *
 * Distinct from the tenant LoginPage — separate URL, separate copy,
 * separate JWT issuer (`fabt-platform`). Spec scenarios covered:
 *   - Heading "Platform Operator Sign-In"
 *   - Subheading with cross-link to tenant /login
 *   - First-time login → mfa-setup-scoped token → /platform/mfa-enroll
 *   - Subsequent login → mfa-verify-scoped token → /platform/mfa-verify
 *   - Failed login → generic "Invalid credentials" (no email-vs-password leak)
 *
 * Also surfaces the "Session expired" toast picked up from
 * sessionStorage when set by the banner countdown's tick-zero handler.
 */

import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { color } from '../../theme/colors';
import { usePlatformAuth } from '../../auth/PlatformAuthContext';

const SESSION_EXPIRED_TOAST_KEY = 'fabt.platform.toast.session-expired';

interface LoginResponse {
  scope: 'mfa-setup' | 'mfa-verify';
  token: string;
  expiresInSeconds: number;
}

export default function PlatformLogin() {
  const navigate = useNavigate();
  const { login } = usePlatformAuth();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [sessionExpiredToast, setSessionExpiredToast] = useState(false);

  // One-shot read of the session-expired toast flag set by the banner's
  // tick-zero handler. Clear immediately so a manual refresh on the
  // login page doesn't keep flashing the toast.
  useEffect(() => {
    if (sessionStorage.getItem(SESSION_EXPIRED_TOAST_KEY) === 'true') {
      setSessionExpiredToast(true);
      sessionStorage.removeItem(SESSION_EXPIRED_TOAST_KEY);
    }
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    setSubmitting(true);
    setErrorMsg(null);
    try {
      const response = await fetch('/api/v1/auth/platform/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      if (response.status !== 200) {
        // 401, 429 (rate-limit), or 5xx → show generic error. The exact
        // status is not surfaced to avoid email-vs-password disclosure.
        if (response.status === 429) {
          setErrorMsg('Too many login attempts from this IP. Try again later.');
        } else {
          setErrorMsg('Invalid credentials');
        }
        return;
      }
      const body = (await response.json()) as LoginResponse;
      login(body.token);
      if (body.scope === 'mfa-setup') {
        navigate('/platform/mfa-enroll', { replace: true });
      } else {
        navigate('/platform/mfa-verify', { replace: true });
      }
    } catch {
      setErrorMsg("Couldn't reach server. Check your connection and try again.");
    } finally {
      setSubmitting(false);
    }
  };

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
      data-testid="platform-login-main"
    >
      <h1 style={{ marginTop: 0, fontSize: '1.5rem' }}>
        Platform Operator Sign-In
      </h1>
      <p style={{ color: color.textSecondary, fontSize: '0.875rem', lineHeight: 1.5 }}>
        This is for FABT platform staff only. If you're a CoC administrator,{' '}
        <a
          href="/login"
          data-testid="platform-login-tenant-crosslink"
          style={{ color: color.primaryText }}
        >
          go to your CoC sign-in page →
        </a>
      </p>
      {sessionExpiredToast && (
        <div
          role="status"
          aria-live="polite"
          data-testid="platform-login-session-expired-toast"
          style={{
            backgroundColor: color.warningBg,
            color: color.warning,
            padding: '0.75rem',
            borderRadius: '4px',
            marginBottom: '1rem',
            fontSize: '0.875rem',
          }}
        >
          Session expired — please sign in again.
        </div>
      )}
      <form onSubmit={handleSubmit} noValidate>
        <label style={{ display: 'block', marginBottom: '1rem' }}>
          <span style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
            Email
          </span>
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoFocus
            autoComplete="username"
            data-testid="platform-login-email"
            style={{
              width: '100%',
              padding: '0.5rem',
              fontSize: '1rem',
              borderRadius: '4px',
              border: `1px solid ${color.border}`,
              backgroundColor: color.bg,
              color: color.text,
            }}
          />
        </label>
        <label style={{ display: 'block', marginBottom: '1rem' }}>
          <span style={{ display: 'block', marginBottom: '0.25rem', fontWeight: 600 }}>
            Password
          </span>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
            data-testid="platform-login-password"
            style={{
              width: '100%',
              padding: '0.5rem',
              fontSize: '1rem',
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
            data-testid="platform-login-error"
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
          data-testid="platform-login-submit"
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
          {submitting ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  );
}
