import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { useAuth } from '../auth/useAuth';
import { getDefaultRouteForRoles } from '../auth/AuthGuard';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';
import { api, ApiError } from '../services/api';

interface OAuth2Provider {
  providerName: string;
  loginUrl: string;
}

interface LoginResponse {
  accessToken?: string;
  refreshToken?: string;
  expiresIn?: number;
  mfaRequired?: boolean;
  mfaToken?: string;
  mustChangePassword?: boolean;
}

export function LoginPage() {
  const { login, isAuthenticated, user } = useAuth();
  const navigate = useNavigate();
  const intl = useIntl();

  const [tenantSlug, setTenantSlug] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [oauthProviders, setOauthProviders] = useState<OAuth2Provider[]>([]);
  const [appVersion, setAppVersion] = useState<string | null>(null);

  // Two-phase TOTP login state
  const [mfaToken, setMfaToken] = useState<string | null>(null);
  const [totpCode, setTotpCode] = useState('');
  const [showBackupInput, setShowBackupInput] = useState(false);

  useEffect(() => {
    api.get<{ version: string }>('/api/v1/version')
      .then(res => setAppVersion(res.version))
      .catch(() => {});
  }, []);

  // Handle OAuth2 callback: tokens or error in URL params
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const accessToken = params.get('accessToken');
    const refreshToken = params.get('refreshToken');
    const oauthError = params.get('error');
    const oauthMessage = params.get('message');

    if (accessToken && refreshToken) {
      // Successful OAuth2 login — store tokens and redirect
      login(accessToken, refreshToken);
      window.history.replaceState({}, '', '/login'); // Clean URL
    } else if (oauthError) {
      if (oauthMessage) {
        setError(decodeURIComponent(oauthMessage));
      } else if (oauthError === 'no_account') {
        setError(intl.formatMessage({ id: 'login.oauth.noAccount' }));
      } else if (oauthError === 'email_required') {
        setError(intl.formatMessage({ id: 'login.oauth.emailRequired' }));
      } else {
        setError(intl.formatMessage({ id: 'login.oauth.error' }));
      }
      window.history.replaceState({}, '', '/login'); // Clean URL
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (isAuthenticated && user) {
      navigate(getDefaultRouteForRoles(user.roles), { replace: true });
    }
  }, [isAuthenticated, user, navigate]);

  useEffect(() => {
    if (!tenantSlug.trim()) {
      setOauthProviders([]);
      return;
    }

    const timeout = setTimeout(async () => {
      try {
        const providers = await api.get<OAuth2Provider[]>(
          `/api/v1/tenants/${encodeURIComponent(tenantSlug)}/oauth2-providers/public`
        );
        setOauthProviders(providers);
      } catch {
        setOauthProviders([]);
      }
    }, 500);

    return () => clearTimeout(timeout);
  }, [tenantSlug]);

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const response = await api.post<LoginResponse>('/api/v1/auth/login', {
        tenantSlug,
        email,
        password,
      });

      if (response.mfaRequired && response.mfaToken) {
        // Two-phase login: password correct, TOTP required
        setMfaToken(response.mfaToken);
        setTotpCode('');
        setError(null);
      } else if (response.accessToken && response.refreshToken) {
        login(response.accessToken, response.refreshToken, response.expiresIn);
      }
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

  const handleTotpVerify = async () => {
    if (!mfaToken || totpCode.length < 6) return;
    setLoading(true);
    setError(null);
    try {
      const response = await api.post<LoginResponse>('/api/v1/auth/verify-totp', {
        mfaToken,
        code: totpCode,
      });
      if (response.accessToken && response.refreshToken) {
        login(response.accessToken, response.refreshToken, response.expiresIn);
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err.message);
      } else {
        setError(intl.formatMessage({ id: 'login.error' }));
      }
      setTotpCode('');
    } finally {
      setLoading(false);
    }
  };

  const handleOAuthLogin = (provider: OAuth2Provider) => {
    window.location.href = provider.loginUrl;
  };

  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        backgroundColor: color.bgTertiary,
        padding: '20px',
      }}
    >
      <div
        style={{
          backgroundColor: color.bg,
          borderRadius: '12px',
          padding: '40px',
          width: '100%',
          maxWidth: '420px',
          boxShadow: '0 4px 6px rgba(0,0,0,0.07)',
        }}
      >
        <h1
          style={{
            fontSize: text['2xl'],
            fontWeight: weight.bold,
            textAlign: 'center',
            marginBottom: '8px',
            color: color.primaryText,
          }}
        >
          <FormattedMessage id="app.name" />
        </h1>
        <p
          style={{
            fontSize: text.base,
            fontWeight: weight.medium,
            textAlign: 'center',
            marginBottom: '24px',
            color: color.textTertiary,
            fontStyle: 'italic',
          }}
        >
          <FormattedMessage id="app.tagline" />
        </p>
        <h2
          style={{
            fontSize: text.lg,
            fontWeight: weight.medium,
            textAlign: 'center',
            marginBottom: '32px',
            color: color.textMuted,
          }}
        >
          <FormattedMessage id="login.title" />
        </h2>

        {error && (
          <div
            style={{
              backgroundColor: color.errorBg,
              color: color.error,
              padding: '12px 16px',
              borderRadius: '8px',
              marginBottom: '20px',
              fontSize: text.base,
            }}
            role="alert"
          >
            {error}
          </div>
        )}

        {mfaToken ? (
          /* Two-phase TOTP verification screen */
          <div data-testid="totp-verify-screen">
            <p style={{ fontSize: text.sm, color: color.textTertiary, textAlign: 'center', marginBottom: 16 }}>
              <FormattedMessage id="totp.loginPrompt" />
            </p>
            <input
              data-testid="totp-login-input"
              type="text"
              inputMode="numeric"
              maxLength={showBackupInput ? 8 : 6}
              value={totpCode}
              onChange={(e) => {
                const v = showBackupInput ? e.target.value.toUpperCase() : e.target.value.replace(/\D/g, '');
                setTotpCode(v);
                // Auto-submit on 6 digits (TOTP)
                if (!showBackupInput && v.length === 6) {
                  setTimeout(() => handleTotpVerify(), 100);
                }
              }}
              autoFocus
              placeholder={showBackupInput ? 'XXXXXXXX' : '000000'}
              style={{
                width: '100%', padding: 14, borderRadius: 8, border: `2px solid ${color.border}`,
                fontSize: text.xl, fontFamily: 'monospace', textAlign: 'center', letterSpacing: '0.3em',
                marginBottom: 12,
              }}
            />
            <button
              data-testid="totp-login-submit"
              onClick={handleTotpVerify}
              disabled={loading || totpCode.length < 6}
              style={{
                width: '100%', padding: 12, borderRadius: 10, border: 'none',
                backgroundColor: color.primary, color: color.textInverse,
                fontSize: text.base, fontWeight: weight.bold, cursor: 'pointer', minHeight: 44,
                marginBottom: 12,
              }}
            >
              {loading ? '...' : intl.formatMessage({ id: 'totp.verifyButton' })}
            </button>
            <div style={{ textAlign: 'center' }}>
              <button
                data-testid="totp-use-backup"
                onClick={() => { setShowBackupInput(!showBackupInput); setTotpCode(''); }}
                style={{
                  background: 'none', border: 'none', color: color.primaryText,
                  fontSize: text.xs, cursor: 'pointer', textDecoration: 'underline',
                }}
              >
                {showBackupInput
                  ? intl.formatMessage({ id: 'totp.useAuthenticator' })
                  : intl.formatMessage({ id: 'totp.useBackupCode' })}
              </button>
            </div>
          </div>
        ) : (
        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: '16px' }}>
            <label
              htmlFor="tenant"
              style={{ display: 'block', marginBottom: '6px', fontSize: text.base, fontWeight: weight.medium, color: color.textSecondary }}
            >
              <FormattedMessage id="login.tenant" />
            </label>
            <input
              id="tenant"
              type="text"
              value={tenantSlug}
              onChange={(e) => setTenantSlug(e.target.value)}
              placeholder="my-organization"
              data-testid="login-tenant-slug"
              style={{
                width: '100%',
                padding: '12px',
                borderRadius: '8px',
                border: `1px solid ${color.borderMedium}`,
                fontSize: text.md,
                minHeight: '44px',
                boxSizing: 'border-box',
              }}
            />
          </div>

          <div style={{ marginBottom: '16px' }}>
            <label
              htmlFor="email"
              style={{ display: 'block', marginBottom: '6px', fontSize: text.base, fontWeight: weight.medium, color: color.textSecondary }}
            >
              <FormattedMessage id="login.email" />
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              data-testid="login-email"
              style={{
                width: '100%',
                padding: '12px',
                borderRadius: '8px',
                border: `1px solid ${color.borderMedium}`,
                fontSize: text.md,
                minHeight: '44px',
                boxSizing: 'border-box',
              }}
            />
          </div>

          <div style={{ marginBottom: '24px' }}>
            <label
              htmlFor="password"
              style={{ display: 'block', marginBottom: '6px', fontSize: text.base, fontWeight: weight.medium, color: color.textSecondary }}
            >
              <FormattedMessage id="login.password" />
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              data-testid="login-password"
              style={{
                width: '100%',
                padding: '12px',
                borderRadius: '8px',
                border: `1px solid ${color.borderMedium}`,
                fontSize: text.md,
                minHeight: '44px',
                boxSizing: 'border-box',
              }}
            />
          </div>

          <button
            type="submit"
            disabled={loading}
            data-testid="login-submit"
            style={{
              width: '100%',
              padding: '14px',
              backgroundColor: loading ? color.primaryDisabled : color.primary,
              color: color.textInverse,
              border: 'none',
              borderRadius: '8px',
              fontSize: text.md,
              fontWeight: weight.semibold,
              cursor: loading ? 'not-allowed' : 'pointer',
              minHeight: '44px',
            }}
          >
            <FormattedMessage id="login.submit" />
          </button>
        </form>
        )}

        {!mfaToken && (
          <div style={{ display: 'flex', justifyContent: 'center', gap: 16, marginTop: 12 }}>
            <a href="/login/access-code" data-testid="login-access-code-link" style={{
              fontSize: text.xs, color: color.primaryText, textDecoration: 'none',
            }}>
              <FormattedMessage id="login.useAccessCode" />
            </a>
          </div>
        )}

        {oauthProviders.length > 0 && !mfaToken && (
          <div style={{ marginTop: '24px' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                marginBottom: '16px',
              }}
            >
              <div style={{ flex: 1, height: '1px', backgroundColor: color.border }} />
              <span style={{ fontSize: text.sm, color: color.textMuted }}>
                <FormattedMessage id="login.oauth.divider" />
              </span>
              <div style={{ flex: 1, height: '1px', backgroundColor: color.border }} />
            </div>
            {oauthProviders.map((provider) => {
              const isGoogle = provider.providerName.toLowerCase().includes('google');
              const isMicrosoft = provider.providerName.toLowerCase().includes('microsoft');
              return (
                <button
                  key={provider.providerName}
                  onClick={() => handleOAuthLogin(provider)}
                  style={{
                    width: '100%',
                    padding: '12px 16px',
                    backgroundColor: isGoogle ? '#ffffff' : isMicrosoft ? '#2f2f2f' : '#ffffff',
                    color: isGoogle ? '#3c4043' : isMicrosoft ? '#ffffff' : '#374151',
                    border: isGoogle ? '1px solid #dadce0' : isMicrosoft ? 'none' : `1px solid ${color.borderMedium}`,
                    borderRadius: '8px',
                    fontSize: text.base,
                    fontWeight: weight.medium,
                    cursor: 'pointer',
                    marginBottom: '8px',
                    minHeight: '44px',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: '10px',
                  }}
                >
                  {isGoogle && <FormattedMessage id="login.oauth.google" />}
                  {isMicrosoft && <FormattedMessage id="login.oauth.microsoft" />}
                  {!isGoogle && !isMicrosoft && (
                    <FormattedMessage id="login.oauth.provider" values={{ name: provider.providerName }} />
                  )}
                </button>
              );
            })}
          </div>
        )}
        {appVersion && (
          <div
            data-testid="app-version"
            style={{
              marginTop: '24px',
              paddingTop: '16px',
              borderTop: `1px solid ${color.border}`,
              fontSize: text.xs,
              color: color.textMuted,
              textAlign: 'center',
            }}
          >
            v{appVersion}
          </div>
        )}
      </div>
    </div>
  );
}
