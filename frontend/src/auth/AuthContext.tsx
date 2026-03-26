import { createContext, useState, useCallback, useEffect, type ReactNode } from 'react';

export interface DecodedUser {
  userId: string;
  tenantId: string;
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
      roles: payload.roles || [],
      dvAccess: payload.dvAccess === true,
      exp: payload.exp || 0,
    };
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() =>
    localStorage.getItem('fabt_access_token')
  );
  const [refreshToken, setRefreshToken] = useState<string | null>(() =>
    localStorage.getItem('fabt_refresh_token')
  );
  const [user, setUser] = useState<DecodedUser | null>(() => {
    const stored = localStorage.getItem('fabt_access_token');
    return stored ? decodeJwtPayload(stored) : null;
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

  useEffect(() => {
    if (user && user.exp * 1000 < Date.now()) {
      logout();
    }
  }, [user, logout]);

  const isAuthenticated = token !== null && user !== null;

  // Expose refreshToken in context for api.ts token refresh
  // Store it as a module-level ref so api.ts can access it
  if (refreshToken) {
    (window as unknown as Record<string, string>).__fabt_refresh_token = refreshToken;
  }

  return (
    <AuthContext.Provider value={{ token, user, isAuthenticated, expiresIn, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}
