/**
 * Hook for fetching + caching the platform operator's `/me` metadata.
 *
 * Used by the persistent banner (for email + countdown anchor) and the
 * dashboard (for last-login + MFA-enrolled timestamps + backup-codes
 * remaining badge). Cached in component state per mount; the dashboard
 * may explicitly refetch after destructive actions if needed.
 */

import { useEffect, useState } from 'react';
import { platformFetch } from './platformApi';

export interface PlatformOperatorMe {
  id: string;
  email: string;
  mfaEnabled: boolean;
  lastLoginAt: string | null;
  mfaEnrolledAt: string | null;
  backupCodesRemaining: number;
}

interface State {
  data: PlatformOperatorMe | null;
  /** True while the initial fetch is in flight. */
  loading: boolean;
  /** Set on network error. 410 (operator anonymized) is a separate concern
   *  the caller can detect via `data === null && !loading && !error`. */
  error: Error | null;
}

export function useOperatorMetadata(): State {
  const [state, setState] = useState<State>({
    data: null,
    loading: true,
    error: null,
  });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const response = await platformFetch('/api/v1/auth/platform/me');
        if (cancelled) return;
        if (response.status === 200) {
          const data = (await response.json()) as PlatformOperatorMe;
          setState({ data, loading: false, error: null });
          return;
        }
        // 401/403 trigger redirects inside platformFetch; this branch
        // is only reachable for 410 (anonymized) or unexpected 4xx/5xx.
        // Mark loading=false but keep data null; the caller decides UX.
        setState({ data: null, loading: false, error: null });
      } catch (err) {
        if (cancelled) return;
        setState({
          data: null,
          loading: false,
          error: err instanceof Error ? err : new Error(String(err)),
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return state;
}
