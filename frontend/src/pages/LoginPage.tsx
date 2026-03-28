import { useState, useEffect, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { FormattedMessage, useIntl } from 'react-intl';
import { useAuth } from '../auth/useAuth';
import { getDefaultRouteForRoles } from '../auth/AuthGuard';
import { text, weight } from '../theme/typography';
import { api, ApiError } from '../services/api';

interface OAuth2Provider {
  providerName: string;
  loginUrl: string;
}

interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
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

      login(response.accessToken, response.refreshToken, response.expiresIn);
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
        backgroundColor: '#f3f4f6',
        padding: '20px',
      }}
    >
      <div
        style={{
          backgroundColor: '#ffffff',
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
            color: '#1a56db',
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
            color: '#475569',
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
            color: '#6b7280',
          }}
        >
          <FormattedMessage id="login.title" />
        </h2>

        {error && (
          <div
            style={{
              backgroundColor: '#fef2f2',
              color: '#991b1b',
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

        <form onSubmit={handleSubmit}>
          <div style={{ marginBottom: '16px' }}>
            <label
              htmlFor="tenant"
              style={{ display: 'block', marginBottom: '6px', fontSize: text.base, fontWeight: weight.medium, color: '#374151' }}
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
                border: '1px solid #d1d5db',
                fontSize: text.md,
                minHeight: '44px',
                boxSizing: 'border-box',
              }}
            />
          </div>

          <div style={{ marginBottom: '16px' }}>
            <label
              htmlFor="email"
              style={{ display: 'block', marginBottom: '6px', fontSize: text.base, fontWeight: weight.medium, color: '#374151' }}
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
                border: '1px solid #d1d5db',
                fontSize: text.md,
                minHeight: '44px',
                boxSizing: 'border-box',
              }}
            />
          </div>

          <div style={{ marginBottom: '24px' }}>
            <label
              htmlFor="password"
              style={{ display: 'block', marginBottom: '6px', fontSize: text.base, fontWeight: weight.medium, color: '#374151' }}
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
                border: '1px solid #d1d5db',
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
              backgroundColor: loading ? '#93c5fd' : '#1a56db',
              color: '#ffffff',
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

        {oauthProviders.length > 0 && (
          <div style={{ marginTop: '24px' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '12px',
                marginBottom: '16px',
              }}
            >
              <div style={{ flex: 1, height: '1px', backgroundColor: '#e5e7eb' }} />
              <span style={{ fontSize: text.sm, color: '#9ca3af' }}>
                <FormattedMessage id="login.oauth.divider" />
              </span>
              <div style={{ flex: 1, height: '1px', backgroundColor: '#e5e7eb' }} />
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
                    border: isGoogle ? '1px solid #dadce0' : isMicrosoft ? 'none' : '1px solid #d1d5db',
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
      </div>
    </div>
  );
}
