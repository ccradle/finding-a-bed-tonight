import { createContext, useState, useCallback, useEffect, type ReactNode } from 'react';

export interface DecodedUser {
  userId: string;
  tenantId: string;
  tenantName: string;
  displayName: string;
  roles: string[];
  dvAccess: boolean;
  exp: number;
}

export interface AuthContextType {
  token: string | null;
  user: DecodedUser | null;
  isAuthenticated: boolean;
  expiresIn: number;
  login: (accessToken: string, refreshToken: string, expiresIn?: number) => void;
  logout: () => void;
}

// eslint-disable-next-line react-refresh/only-export-components -- Standard pattern: context + provider in same file
export const AuthContext = createContext<AuthContextType>({
  token: null,
  user: null,
  isAuthenticated: false,
  expiresIn: 900,
  login: () => {},
  logout: () => {},
});

function decodeJwtPayload(token: string): DecodedUser | null {
  try {
    const base64Url = token.split('.')[1];
    if (!base64Url) return null;
    const base64 = base64Url.replace(/-/g, '+').replace(/_/g, '/');
    const jsonPayload = decodeURIComponent(
      atob(base64)
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join('')
    );
    const payload = JSON.parse(jsonPayload);
    return {
      userId: payload.sub || payload.userId || '',
      tenantId: payload.tenantId || '',
      tenantName: payload.tenantName || '',
      displayName: payload.displayName || '',
      roles: payload.roles || [],
      dvAccess: payload.dvAccess === true,
      exp: payload.exp || 0,
    };
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  // Check expiry during initialization — not in an effect (avoids cascading setState)
  const [token, setToken] = useState<string | null>(() => {
    const stored = localStorage.getItem('fabt_access_token');
    if (stored) {
      const decoded = decodeJwtPayload(stored);
      if (decoded && decoded.exp * 1000 < Date.now()) {
        localStorage.removeItem('fabt_access_token');
        localStorage.removeItem('fabt_refresh_token');
        return null;
      }
    }
    return stored;
  });
  const [refreshToken, setRefreshToken] = useState<string | null>(() =>
    localStorage.getItem('fabt_refresh_token')
  );
  const [user, setUser] = useState<DecodedUser | null>(() => {
    const stored = localStorage.getItem('fabt_access_token');
    if (!stored) return null;
    const decoded = decodeJwtPayload(stored);
    if (decoded && decoded.exp * 1000 < Date.now()) return null;
    return decoded;
  });

  const [expiresIn, setExpiresIn] = useState(900); // default 15 min

  const login = useCallback((accessToken: string, newRefreshToken: string, tokenExpiresIn?: number) => {
    localStorage.setItem('fabt_access_token', accessToken);
    localStorage.setItem('fabt_refresh_token', newRefreshToken);
    setToken(accessToken);
    setRefreshToken(newRefreshToken);
    setUser(decodeJwtPayload(accessToken));
    if (tokenExpiresIn) setExpiresIn(tokenExpiresIn);
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem('fabt_access_token');
    localStorage.removeItem('fabt_refresh_token');
    setToken(null);
    setRefreshToken(null);
    setUser(null);
  }, []);

  const isAuthenticated = token !== null && user !== null;

  // Expose refreshToken for api.ts token refresh via window global.
  // Must be in useEffect — modifying external state during render triggers lint error.
  useEffect(() => {
    if (refreshToken) {
      (window as unknown as Record<string, string>).__fabt_refresh_token = refreshToken;
    }
  }, [refreshToken]);

  return (
    <AuthContext.Provider value={{ token, user, isAuthenticated, expiresIn, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
