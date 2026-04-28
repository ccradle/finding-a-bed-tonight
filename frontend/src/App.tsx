import { useState, useEffect, lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { IntlProvider } from 'react-intl';
import { AuthProvider } from './auth/AuthContext';
import { AuthGuard } from './auth/AuthGuard';
import { getDefaultRouteForRoles } from './auth/AuthGuard';
import { PlatformAuthProvider } from './auth/PlatformAuthContext';
import {
  PlatformProtectedRoute,
  PlatformRedirectIfAuthenticated,
} from './pages/platform/PlatformProtectedRoute';
import { useAuth } from './auth/useAuth';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';

/**
 * Build-time gate for the F11 platform-operator UI (`/platform/*` routes).
 *
 * The `if (... === 'true')` guard at module top-level lets Rollup
 * dead-code-eliminate the dynamic `import()` literal when
 * VITE_PLATFORM_UI_ENABLED is not 'true' at build time. Without this
 * top-level guard, React.lazy still emits the chunk; the env-var check
 * inside the lazy callback is too late.
 *
 * When false, `PlatformPlaceholder` is null and the `/platform/*` routes
 * fall through to the catch-all NotFound. See OpenSpec
 * platform-operator-ui design.md Decision D7.
 */
const PlatformLayout =
  import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true'
    ? lazy(() => import('./pages/platform/PlatformLayout'))
    : null;
const PlatformLogin =
  import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true'
    ? lazy(() => import('./pages/platform/PlatformLogin'))
    : null;
const PlatformMfaEnroll =
  import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true'
    ? lazy(() => import('./pages/platform/PlatformMfaEnroll'))
    : null;
const PlatformMfaVerify =
  import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true'
    ? lazy(() => import('./pages/platform/PlatformMfaVerify'))
    : null;
const PlatformDashboard =
  import.meta.env.VITE_PLATFORM_UI_ENABLED === 'true'
    ? lazy(() => import('./pages/platform/PlatformDashboard'))
    : null;
import { CoordinatorDashboard } from './pages/CoordinatorDashboard';
import { OutreachSearch } from './pages/OutreachSearch';
import { MyPastHoldsPage } from './pages/MyPastHoldsPage';
import { AdminPanel } from './pages/admin/AdminPanel';
import { ShelterForm } from './pages/ShelterForm';
import { ShelterEditPage } from './pages/ShelterEditPage';
import { HsdsImportPage } from './pages/HsdsImportPage';
import { TwoOneOneImportPage } from './pages/TwoOneOneImportPage';
import { TotpEnrollmentPage } from './pages/TotpEnrollmentPage';
import { AccessCodeLoginPage } from './pages/AccessCodeLoginPage';
import { ForgotPasswordPage } from './pages/ForgotPasswordPage';
import { ResetPasswordPage } from './pages/ResetPasswordPage';
import enMessages from './i18n/en.json';
import esMessages from './i18n/es.json';

const messages: Record<string, Record<string, string>> = {
  en: enMessages,
  es: esMessages,
};

function RoleRedirect() {
  const { isAuthenticated, user } = useAuth();
  if (!isAuthenticated || !user) {
    return <Navigate to="/login" replace />;
  }
  return <Navigate to={getDefaultRouteForRoles(user.roles)} replace />;
}

function AppRoutes({ locale, onLocaleChange }: { locale: string; onLocaleChange: (l: string) => void }) {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/login/access-code" element={<AccessCodeLoginPage />} />
      <Route path="/login/forgot-password" element={<ForgotPasswordPage />} />
      <Route path="/login/reset-password" element={<ResetPasswordPage />} />
      <Route path="/settings/totp" element={
        <AuthGuard allowedRoles={['OUTREACH_WORKER', 'COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN']}>
          <Layout locale={locale} onLocaleChange={onLocaleChange}>
            <TotpEnrollmentPage />
          </Layout>
        </AuthGuard>
      } />
      <Route
        path="/coordinator/*"
        element={
          <AuthGuard allowedRoles={['COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN']}>
            <Layout locale={locale} onLocaleChange={onLocaleChange}>
              <Routes>
                <Route index element={<CoordinatorDashboard />} />
                <Route path="shelters/new" element={<ShelterForm />} />
                <Route path="shelters/:id/edit" element={<ShelterEditPage />} />
                <Route path="import/hsds" element={<HsdsImportPage />} />
                <Route path="import/211" element={<TwoOneOneImportPage />} />
              </Routes>
            </Layout>
          </AuthGuard>
        }
      />
      <Route
        path="/outreach/*"
        element={
          <AuthGuard allowedRoles={['OUTREACH_WORKER', 'COC_ADMIN', 'PLATFORM_ADMIN']}>
            <Layout locale={locale} onLocaleChange={onLocaleChange}>
              <Routes>
                <Route index element={<OutreachSearch />} />
                <Route path="my-holds" element={<MyPastHoldsPage />} />
              </Routes>
            </Layout>
          </AuthGuard>
        }
      />
      <Route
        path="/admin/*"
        element={
          <AuthGuard allowedRoles={['COC_ADMIN', 'PLATFORM_ADMIN']}>
            <Layout locale={locale} onLocaleChange={onLocaleChange}>
              <Routes>
                <Route index element={<AdminPanel />} />
              </Routes>
            </Layout>
          </AuthGuard>
        }
      />
      {PlatformLayout && PlatformLogin && PlatformMfaEnroll && PlatformMfaVerify && PlatformDashboard && (
        <Route
          path="/platform"
          element={
            <Suspense fallback={
              <div
                role="status"
                aria-live="polite"
                style={{
                  padding: '2rem',
                  textAlign: 'center',
                  color: 'var(--color-text-secondary)',
                }}
              >
                Loading platform operator console…
              </div>
            }>
              <PlatformLayout />
            </Suspense>
          }
        >
          {/* Login: open to anyone NOT yet authenticated; if already
              authenticated, kick to the natural next step. */}
          <Route
            path="login"
            element={
              <PlatformRedirectIfAuthenticated>
                <PlatformLogin />
              </PlatformRedirectIfAuthenticated>
            }
          />
          {/* MFA enroll: requires a freshly-issued mfa-setup-scoped token. */}
          <Route
            path="mfa-enroll"
            element={
              <PlatformProtectedRoute requiredScope="mfa-setup">
                <PlatformMfaEnroll />
              </PlatformProtectedRoute>
            }
          />
          {/* MFA verify: requires a freshly-issued mfa-verify-scoped token. */}
          <Route
            path="mfa-verify"
            element={
              <PlatformProtectedRoute requiredScope="mfa-verify">
                <PlatformMfaVerify />
              </PlatformProtectedRoute>
            }
          />
          {/* Dashboard: requires post-MFA access token (default scope). */}
          <Route
            path="dashboard"
            element={
              <PlatformProtectedRoute>
                <PlatformDashboard />
              </PlatformProtectedRoute>
            }
          />
          {/* Catch-all under /platform/* — route guard kicks unauthenticated
              operators to /platform/login; otherwise lands on dashboard. */}
          <Route
            path="*"
            element={
              <PlatformProtectedRoute>
                <PlatformDashboard />
              </PlatformProtectedRoute>
            }
          />
        </Route>
      )}
      <Route path="/" element={<RoleRedirect />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  const [locale, setLocale] = useState(() => {
    const stored = localStorage.getItem('fabt_locale');
    return stored && messages[stored] ? stored : 'en';
  });

  // WCAG 3.1.1 — set lang attribute on initial load
  useEffect(() => { document.documentElement.lang = locale; }, [locale]);

  const handleLocaleChange = (newLocale: string) => {
    setLocale(newLocale);
    localStorage.setItem('fabt_locale', newLocale);
    // WCAG 3.1.1 — update lang attribute so screen readers use correct voice
    document.documentElement.lang = newLocale;
  };

  return (
    <IntlProvider locale={locale} messages={messages[locale]} defaultLocale="en">
      <AuthProvider>
        <PlatformAuthProvider>
          <BrowserRouter>
            <AppRoutes locale={locale} onLocaleChange={handleLocaleChange} />
          </BrowserRouter>
        </PlatformAuthProvider>
      </AuthProvider>
    </IntlProvider>
  );
}
