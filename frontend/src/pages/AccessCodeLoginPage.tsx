import { useState, type FormEvent } from 'react';
import { FormattedMessage, useIntl } from 'react-intl';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/useAuth';
import { api, ApiError } from '../services/api';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

/**
 * Access code login page — worker enters admin-generated OTT code.
 * On success, receives JWT with mustChangePassword=true → redirected to password change.
 */

interface AccessCodeResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  mustChangePassword: boolean;
}

export function AccessCodeLoginPage() {
  const intl = useIntl();
  const navigate = useNavigate();
  const { login } = useAuth();

  const [tenantSlug, setTenantSlug] = useState('');
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const response = await api.post<AccessCodeResponse>('/api/v1/auth/access-code', {
        tenantSlug, email, code,
      });
      login(response.accessToken, response.refreshToken, response.expiresIn);
      // TODO: detect mustChangePassword and redirect to password change
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(intl.formatMessage({ id: 'login.error' }));
      }
    } finally {
      setLoading(false);
    }
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
        <h2 style={{ fontSize: text.lg, fontWeight: weight.medium, textAlign: 'center', marginBottom: 24, color: color.textMuted }}>
          <FormattedMessage id="totp.accessCodeTitle" />
        </h2>

        <p style={{ fontSize: text.sm, color: color.textTertiary, textAlign: 'center', marginBottom: 20 }}>
          <FormattedMessage id="totp.accessCodeDescription" />
        </p>

        {error && (
          <div role="alert" style={{
            backgroundColor: color.errorBg, color: color.error,
            padding: '12px 16px', borderRadius: 8, marginBottom: 20, fontSize: text.base,
          }}>
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: 16 }}>
            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 6 }}>
              <FormattedMessage id="login.tenant" />
            </label>
            <input data-testid="access-code-tenant" type="text" value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)} placeholder="my-organization"
              style={{ width: '100%', padding: 12, borderRadius: 8, border: `2px solid ${color.border}`, fontSize: text.base }} />
          </div>

          <div style={{ marginBottom: 16 }}>
            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 6 }}>
              <FormattedMessage id="login.email" />
            </label>
            <input data-testid="access-code-email" type="email" value={email}
              onChange={(e) => setEmail(e.target.value)}
              style={{ width: '100%', padding: 12, borderRadius: 8, border: `2px solid ${color.border}`, fontSize: text.base }} />
          </div>

          <div style={{ marginBottom: 20 }}>
            <label style={{ display: 'block', fontSize: text.sm, fontWeight: weight.semibold, color: color.text, marginBottom: 6 }}>
              <FormattedMessage id="totp.accessCodePlaceholder" />
            </label>
            <input data-testid="access-code-input" type="text" value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              style={{
                width: '100%', padding: 14, borderRadius: 8, border: `2px solid ${color.border}`,
                fontSize: text.xl, fontFamily: 'monospace', textAlign: 'center', letterSpacing: '0.2em',
              }} />
          </div>

          <button data-testid="access-code-submit" type="submit" disabled={loading || !code || !email || !tenantSlug}
            style={{
              width: '100%', padding: 14, borderRadius: 10, border: 'none',
              backgroundColor: color.primary, color: color.textInverse,
              fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer', minHeight: 44,
            }}>
            {loading ? '...' : intl.formatMessage({ id: 'totp.accessCodeSubmit' })}
          </button>
        </form>

        <div style={{ textAlign: 'center', marginTop: 16 }}>
          <button onClick={() => navigate('/login')} style={{
            background: 'none', border: 'none', color: color.primaryText,
            fontSize: text.xs, cursor: 'pointer', textDecoration: 'underline',
          }}>
            <FormattedMessage id="login.title" />
          </button>
        </div>
      </div>
    </div>
  );
}
