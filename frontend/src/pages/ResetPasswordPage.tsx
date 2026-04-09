import { useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

/**
 * Reset Password page — accepts token from URL, new password + confirm.
 * Submits to POST /api/v1/auth/reset-password.
 */
export function ResetPasswordPage() {
  const intl = useIntl();
  const [searchParams] = useSearchParams();
  const token = searchParams.get('token') || '';

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!token) {
      setError(intl.formatMessage({ id: 'resetPassword.invalidToken' }));
      return;
    }
    if (newPassword.length < 12) {
      setError(intl.formatMessage({ id: 'resetPassword.tooShort' }));
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(intl.formatMessage({ id: 'resetPassword.mismatch' }));
      return;
    }

    setLoading(true);
    try {
      const resp = await fetch('/api/v1/auth/reset-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token, newPassword }),
      });

      if (resp.ok) {
        setSuccess(true);
      } else {
        const data = await resp.json().catch(() => ({}));
        setError(data.message || intl.formatMessage({ id: 'resetPassword.error' }));
      }
    } catch {
      setError(intl.formatMessage({ id: 'resetPassword.error' }));
    }
    setLoading(false);
  };

  return (
    <div style={{
      display: 'flex', justifyContent: 'center', alignItems: 'center',
      minHeight: '100vh', backgroundColor: color.bgTertiary, padding: 20,
    }}>
      <div style={{
        backgroundColor: color.bg, borderRadius: 12, padding: 40,
        width: '100%', maxWidth: 420, boxShadow: '0 4px 6px rgba(0,0,0,0.07)',
      }}>
        <h2 style={{ fontSize: text.lg, fontWeight: weight.medium, color: color.text, marginBottom: 8, textAlign: 'center' }}>
          <FormattedMessage id="resetPassword.title" />
        </h2>

        {success ? (
          <div data-testid="reset-password-success">
            <p style={{ fontSize: text.base, color: color.success, textAlign: 'center', marginBottom: 24 }}>
              <FormattedMessage id="resetPassword.success" />
            </p>
            <div style={{ textAlign: 'center' }}>
              <a href="/login" style={{
                display: 'inline-block', padding: '12px 24px', borderRadius: 10,
                backgroundColor: color.primary, color: color.textInverse,
                fontSize: text.base, fontWeight: weight.bold, textDecoration: 'none',
                minHeight: 44,
              }}>
                <FormattedMessage id="resetPassword.signIn" />
              </a>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            {!token && (
              <p style={{ fontSize: text.sm, color: color.error, textAlign: 'center', marginBottom: 16 }}>
                <FormattedMessage id="resetPassword.noToken" />
              </p>
            )}

            {error && (
              <div style={{
                backgroundColor: color.errorBg, color: color.error,
                padding: '10px 14px', borderRadius: 8, marginBottom: 16, fontSize: text.sm,
              }} data-testid="reset-password-error">
                {error}
              </div>
            )}

            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 4 }}>
              <FormattedMessage id="resetPassword.newPassword" />
            </label>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              data-testid="reset-password-new"
              required
              minLength={12}
              autoComplete="new-password"
              style={{
                width: '100%', padding: '10px 14px', fontSize: text.base,
                border: `1px solid ${color.borderMedium}`, borderRadius: 8,
                backgroundColor: color.bg, color: color.text, marginBottom: 16,
                minHeight: 44, boxSizing: 'border-box',
              }}
            />

            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 4 }}>
              <FormattedMessage id="resetPassword.confirmPassword" />
            </label>
            <input
              type="password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              data-testid="reset-password-confirm"
              required
              minLength={12}
              autoComplete="new-password"
              style={{
                width: '100%', padding: '10px 14px', fontSize: text.base,
                border: `1px solid ${color.borderMedium}`, borderRadius: 8,
                backgroundColor: color.bg, color: color.text, marginBottom: 24,
                minHeight: 44, boxSizing: 'border-box',
              }}
            />

            <button
              type="submit"
              disabled={loading || !token}
              data-testid="reset-password-submit"
              style={{
                width: '100%', padding: '14px', borderRadius: 10,
                backgroundColor: (loading || !token) ? color.primaryDisabled : color.primary,
                color: color.textInverse, border: 'none',
                fontSize: text.base, fontWeight: weight.bold,
                cursor: (loading || !token) ? 'not-allowed' : 'pointer',
                minHeight: 44,
              }}
            >
              {loading
                ? '...'
                : intl.formatMessage({ id: 'resetPassword.submit' })}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
