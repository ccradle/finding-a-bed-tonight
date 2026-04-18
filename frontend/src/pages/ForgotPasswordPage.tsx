import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { api, ApiError } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

/**
 * Forgot Password page — email + tenant slug form.
 * Submits to POST /api/v1/auth/forgot-password.
 *
 * Non-demo: always shows "Check your email" confirmation regardless of
 * whether the email exists (anti-enumeration).
 *
 * Demo: DemoGuardFilter returns `demo_restricted`; we surface that
 * explicitly so users don't wait for an email that will never arrive.
 * Demo mode is public knowledge (findabed.org URL is obviously demo),
 * so no enumeration value is lost by outing the demo-restricted branch.
 */
export function ForgotPasswordPage() {
  const intl = useIntl();
  const [email, setEmail] = useState('');
  const [tenantSlug, setTenantSlug] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [demoBlocked, setDemoBlocked] = useState(false);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!email.trim() || !tenantSlug.trim()) return;

    setLoading(true);
    try {
      await api.post('/api/v1/auth/forgot-password', { email: email.trim(), tenantSlug: tenantSlug.trim() });
    } catch (err) {
      if (err instanceof ApiError && err.error === 'demo_restricted') {
        setDemoBlocked(true);
      }
      // Other errors: silently succeed (anti-enumeration).
    }
    setLoading(false);
    setSubmitted(true);
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
          <FormattedMessage id="forgotPassword.title" />
        </h2>

        {submitted ? (
          <div data-testid={demoBlocked ? 'forgot-password-demo-blocked' : 'forgot-password-confirmation'}>
            <p style={{ fontSize: text.base, color: color.textSecondary, textAlign: 'center', marginBottom: 24 }}>
              <FormattedMessage id={demoBlocked ? 'forgotPassword.demoBlocked' : 'forgotPassword.checkEmail'} />
            </p>
            <p style={{ fontSize: text.sm, color: color.textMuted, textAlign: 'center', marginBottom: 24 }}>
              <FormattedMessage id={demoBlocked ? 'forgotPassword.demoBlockedDetail' : 'forgotPassword.checkEmailDetail'} />
            </p>
            <div style={{ textAlign: 'center' }}>
              <Link to="/login" data-testid="forgot-password-back-button" style={{
                display: 'inline-block', padding: '12px 24px', borderRadius: 10,
                backgroundColor: color.primary, color: color.textInverse,
                fontSize: text.base, fontWeight: weight.bold, textDecoration: 'none',
                minHeight: 44,
              }}>
                <FormattedMessage id="forgotPassword.backToLogin" />
              </Link>
            </div>
          </div>
        ) : (
          <form onSubmit={handleSubmit}>
            <p style={{ fontSize: text.sm, color: color.textMuted, textAlign: 'center', marginBottom: 20 }}>
              <FormattedMessage id="forgotPassword.instructions" />
            </p>

            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 4 }}>
              <FormattedMessage id="login.tenant" />
            </label>
            <input
              type="text"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              placeholder={intl.formatMessage({ id: 'login.organizationPlaceholder' })}
              data-testid="forgot-password-tenant"
              required
              style={{
                width: '100%', padding: '10px 14px', fontSize: text.base,
                border: `1px solid ${color.borderMedium}`, borderRadius: 8,
                backgroundColor: color.bg, color: color.text, marginBottom: 16,
                minHeight: 44, boxSizing: 'border-box',
              }}
            />

            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 4 }}>
              <FormattedMessage id="login.email" />
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder={intl.formatMessage({ id: 'login.emailPlaceholder' })}
              data-testid="forgot-password-email"
              required
              style={{
                width: '100%', padding: '10px 14px', fontSize: text.base,
                border: `1px solid ${color.borderMedium}`, borderRadius: 8,
                backgroundColor: color.bg, color: color.text, marginBottom: 24,
                minHeight: 44, boxSizing: 'border-box',
              }}
            />

            <button
              type="submit"
              disabled={loading}
              data-testid="forgot-password-submit"
              style={{
                width: '100%', padding: '14px', borderRadius: 10,
                backgroundColor: loading ? color.primaryDisabled : color.primary,
                color: color.textInverse, border: 'none',
                fontSize: text.base, fontWeight: weight.bold,
                cursor: loading ? 'not-allowed' : 'pointer',
                minHeight: 44, marginBottom: 16,
              }}
            >
              {loading
                ? intl.formatMessage({ id: 'forgotPassword.sending' })
                : intl.formatMessage({ id: 'forgotPassword.submit' })}
            </button>

            <div style={{ textAlign: 'center' }}>
              <Link to="/login/access-code" data-testid="forgot-password-access-code-link" style={{ fontSize: text.sm, color: color.primaryText, textDecoration: 'none', marginRight: 16 }}>
                <FormattedMessage id="login.useAccessCode" />
              </Link>
              <Link to="/login" data-testid="forgot-password-back-link" style={{ fontSize: text.sm, color: color.primaryText, textDecoration: 'none' }}>
                <FormattedMessage id="forgotPassword.backToLogin" />
              </Link>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
