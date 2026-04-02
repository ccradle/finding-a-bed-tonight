import { FormattedMessage } from 'react-intl';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

/**
 * Forgot Password page — placeholder until email delivery is configured.
 * Directs users to the access code path (admin-generated recovery).
 */
export function ForgotPasswordPage() {
  return (
    <div style={{
      display: 'flex', justifyContent: 'center', alignItems: 'center',
      minHeight: '100vh', backgroundColor: color.bgTertiary, padding: 20,
    }}>
      <div style={{
        backgroundColor: color.bg, borderRadius: 12, padding: 40,
        width: '100%', maxWidth: 420, boxShadow: '0 4px 6px rgba(0,0,0,0.07)',
        textAlign: 'center',
      }}>
        <h2 style={{ fontSize: text.lg, fontWeight: weight.medium, color: color.text, marginBottom: 16 }}>
          <FormattedMessage id="login.forgotPassword" />
        </h2>
        <p style={{ fontSize: text.base, color: color.textTertiary, marginBottom: 24 }}>
          Contact your administrator to receive a one-time access code. They can generate one from the admin panel.
        </p>
        <a href="/login/access-code" style={{
          display: 'inline-block', padding: '12px 24px', borderRadius: 10,
          backgroundColor: color.primary, color: color.textInverse,
          fontSize: text.base, fontWeight: weight.bold, textDecoration: 'none',
          minHeight: 44,
        }}>
          <FormattedMessage id="login.useAccessCode" />
        </a>
        <div style={{ marginTop: 16 }}>
          <a href="/login" style={{ fontSize: text.xs, color: color.primaryText, textDecoration: 'none' }}>
            <FormattedMessage id="login.title" />
          </a>
        </div>
      </div>
    </div>
  );
}
