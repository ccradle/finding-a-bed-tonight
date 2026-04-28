/**
 * Platform-operator authentication context (F11 v0.54).
 *
 * Sibling to {@link AuthContext} — NOT a generalization. Two separate
 * stores, two separate hooks, zero shared mutable state. This keeps the
 * tenant and platform auth flows mechanically distinct so a confused-deputy
 * bug can't accidentally serve a tenant JWT to a platform endpoint or
 * vice versa.
 *
 * Storage: sessionStorage (NOT localStorage) under the namespaced key
 * `fabt.platform.jwt.v1`. Tab close = forced re-login. Per design.md D1.
 *
 * Expiry: 15-minute hard lifetime; no refresh token; the consumer-side
 * route guard checks `isExpired(jwt)` synchronously before rendering
 * children, AND the banner countdown timer triggers a redirect on tick-zero.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import type { ReactNode } from 'react';
import {
  clearPlatformJwt,
  isExpired,
  parseClaims,
  readPlatformJwt,
  writePlatformJwt,
} from '../pages/platform/helpers/platformJwt';
import type { PlatformJwtClaims } from '../pages/platform/helpers/platformJwt';

interface PlatformAuthContextValue {
  /** Raw JWT string from sessionStorage, or null. */
  jwt: string | null;
  /** Parsed claims of the current JWT, or null. */
  claims: PlatformJwtClaims | null;
  /** Convenience: claims.sub if present. */
  operatorId: string | null;
  /** True iff a JWT is present and `exp` is in the future. */
  isAuthenticated: boolean;
  /** True iff the JWT has `mfaVerified=true` (post-MFA access tokens). */
  isMfaVerified: boolean;
  /** Persists the token in sessionStorage and refreshes context state. */
  login: (token: string) => void;
  /** Wipes the token + context state. Does NOT call the backend. */
  logout: () => void;
}

const PlatformAuthContext = createContext<PlatformAuthContextValue | null>(null);

export function PlatformAuthProvider({ children }: { children: ReactNode }) {
  const [jwt, setJwt] = useState<string | null>(() => readPlatformJwt());

  // Listen for cross-tab logout via the storage event so closing one
  // platform tab triggers logout in any other open ones (defense against
  // session leakage if the operator forgot to close another tab).
  useEffect(() => {
    const handler = (e: StorageEvent) => {
      if (e.key === 'fabt.platform.jwt.v1' && e.newValue === null) {
        setJwt(null);
      }
    };
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  }, []);

  const login = useCallback((token: string) => {
    writePlatformJwt(token);
    setJwt(token);
  }, []);

  const logout = useCallback(() => {
    clearPlatformJwt();
    setJwt(null);
  }, []);

  const value = useMemo<PlatformAuthContextValue>(() => {
    const claims = parseClaims(jwt);
    const isAuthenticated = jwt != null && !isExpired(jwt);
    return {
      jwt,
      claims,
      operatorId: claims?.sub ?? null,
      isAuthenticated,
      isMfaVerified: isAuthenticated && claims?.mfaVerified === true,
      login,
      logout,
    };
  }, [jwt, login, logout]);

  return (
    <PlatformAuthContext.Provider value={value}>
      {children}
    </PlatformAuthContext.Provider>
  );
}

export function usePlatformAuth(): PlatformAuthContextValue {
  const ctx = useContext(PlatformAuthContext);
  if (!ctx) {
    throw new Error('usePlatformAuth must be used within a PlatformAuthProvider');
  }
  return ctx;
}
