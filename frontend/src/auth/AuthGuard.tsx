import { Navigate } from 'react-router-dom';
import { useAuth } from './useAuth';
import type { ReactNode } from 'react';

interface AuthGuardProps {
  allowedRoles: string[];
  children: ReactNode;
}

export function getDefaultRouteForRoles(roles: string[]): string {
  if (roles.includes('PLATFORM_ADMIN') || roles.includes('COC_ADMIN')) {
    return '/admin';
  }
  if (roles.includes('COORDINATOR')) {
    return '/coordinator';
  }
  if (roles.includes('OUTREACH_WORKER')) {
    return '/outreach';
  }
  return '/login';
}

export function AuthGuard({ allowedRoles, children }: AuthGuardProps) {
  const { isAuthenticated, user } = useAuth();

  if (!isAuthenticated || !user) {
    return <Navigate to="/login" replace />;
  }

  if (allowedRoles.length > 0) {
    const hasRole = user.roles.some((role) => allowedRoles.includes(role));
    if (!hasRole) {
      const defaultRoute = getDefaultRouteForRoles(user.roles);
      return <Navigate to={defaultRoute} replace />;
    }
  }

  return <>{children}</>;
}
