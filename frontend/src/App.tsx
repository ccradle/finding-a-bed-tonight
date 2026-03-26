import { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { IntlProvider } from 'react-intl';
import { AuthProvider } from './auth/AuthContext';
import { AuthGuard } from './auth/AuthGuard';
import { getDefaultRouteForRoles } from './auth/AuthGuard';
import { useAuth } from './auth/useAuth';
import { Layout } from './components/Layout';
import { LoginPage } from './pages/LoginPage';
import { CoordinatorDashboard } from './pages/CoordinatorDashboard';
import { OutreachSearch } from './pages/OutreachSearch';
import { AdminPanel } from './pages/AdminPanel';
import { ShelterForm } from './pages/ShelterForm';
import { HsdsImportPage } from './pages/HsdsImportPage';
import { TwoOneOneImportPage } from './pages/TwoOneOneImportPage';
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
      <Route
        path="/coordinator/*"
        element={
          <AuthGuard allowedRoles={['COORDINATOR', 'COC_ADMIN', 'PLATFORM_ADMIN']}>
            <Layout locale={locale} onLocaleChange={onLocaleChange}>
              <Routes>
                <Route index element={<CoordinatorDashboard />} />
                <Route path="shelters/new" element={<ShelterForm />} />
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
        <BrowserRouter>
          <AppRoutes locale={locale} onLocaleChange={handleLocaleChange} />
        </BrowserRouter>
      </AuthProvider>
    </IntlProvider>
  );
}
