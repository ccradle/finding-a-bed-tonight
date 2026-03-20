// Dev: Vite proxies /api → localhost:8080 (vite.config.ts), so BASE_URL is empty
// Production (nginx): nginx proxies /api → backend, so BASE_URL is empty
// Production (CDN/separate domain): set VITE_API_URL=https://api.example.com at build time
const BASE_URL = import.meta.env.VITE_API_URL || '';

export interface ErrorResponse {
  status: number;
  error: string;
  message: string;
  details?: Record<string, string>;
}

export class ApiError extends Error {
  public status: number;
  public error: string;
  public details?: Record<string, string>;

  constructor(response: ErrorResponse) {
    super(response.message);
    this.name = 'ApiError';
    this.status = response.status;
    this.error = response.error;
    this.details = response.details;
  }
}

function getAccessToken(): string | null {
  return localStorage.getItem('fabt_access_token');
}

function getRefreshToken(): string | null {
  return localStorage.getItem('fabt_refresh_token');
}

function clearTokens(): void {
  localStorage.removeItem('fabt_access_token');
  localStorage.removeItem('fabt_refresh_token');
}

async function attemptTokenRefresh(): Promise<boolean> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;

  try {
    const response = await fetch(`${BASE_URL}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });

    if (!response.ok) return false;

    const data = await response.json();
    localStorage.setItem('fabt_access_token', data.accessToken);
    if (data.refreshToken) {
      localStorage.setItem('fabt_refresh_token', data.refreshToken);
    }
    return true;
  } catch {
    return false;
  }
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  options?: { headers?: Record<string, string>; isFormData?: boolean }
): Promise<T> {
  const url = path.startsWith('http') ? path : `${BASE_URL}${path}`;

  const headers: Record<string, string> = { ...options?.headers };
  const token = getAccessToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  if (!options?.isFormData) {
    headers['Content-Type'] = 'application/json';
  }

  const fetchOptions: RequestInit = {
    method,
    headers,
  };

  if (body !== undefined) {
    if (options?.isFormData) {
      fetchOptions.body = body as FormData;
    } else {
      fetchOptions.body = JSON.stringify(body);
    }
  }

  let response = await fetch(url, fetchOptions);

  if (response.status === 401) {
    const refreshed = await attemptTokenRefresh();
    if (refreshed) {
      const newToken = getAccessToken();
      if (newToken) {
        headers['Authorization'] = `Bearer ${newToken}`;
      }
      response = await fetch(url, { ...fetchOptions, headers });
    } else {
      clearTokens();
      window.location.href = '/login';
      throw new ApiError({
        status: 401,
        error: 'Unauthorized',
        message: 'Session expired. Please log in again.',
      });
    }
  }

  if (!response.ok) {
    let errorBody: ErrorResponse;
    try {
      errorBody = await response.json();
    } catch {
      errorBody = {
        status: response.status,
        error: response.statusText,
        message: `Request failed with status ${response.status}`,
      };
    }
    throw new ApiError(errorBody);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json();
}

export const api = {
  get<T>(path: string, headers?: Record<string, string>): Promise<T> {
    return request<T>('GET', path, undefined, { headers });
  },

  post<T>(path: string, body?: unknown, options?: { headers?: Record<string, string>; isFormData?: boolean }): Promise<T> {
    return request<T>('POST', path, body, options);
  },

  put<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return request<T>('PUT', path, body, { headers });
  },

  patch<T>(path: string, body?: unknown, headers?: Record<string, string>): Promise<T> {
    return request<T>('PATCH', path, body, { headers });
  },

  delete<T>(path: string, headers?: Record<string, string>): Promise<T> {
    return request<T>('DELETE', path, undefined, { headers });
  },
};
