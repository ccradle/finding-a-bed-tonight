/**
 * Hook for fetching + caching the platform operator's `/me` metadata.
 *
 * Used by the persistent banner (for email + countdown anchor) and the
 * dashboard (for last-login + MFA-enrolled timestamps + backup-codes
 * remaining badge). Cached in component state per mount; the dashboard
 * may explicitly refetch after destructive actions if needed.
 */

import { useCallback, useEffect, useState } from 'react';
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
  /** Set on network error. */
  error: Error | null;
  /** True iff the backend returned 410 — operator's row is anonymized
   *  (or no longer present). Caller (banner / dashboard) renders an
   *  "account removed; contact support" UX and forces logout. */
  anonymized: boolean;
  /** Re-fetch /me. Used by the dashboard after destructive actions
   *  (slice D / future regenerate-codes flow) to refresh the
   *  backupCodesRemaining badge. */
  refetch: () => void;
}

export function useOperatorMetadata(): State {
  const [data, setData] = useState<PlatformOperatorMe | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [anonymized, setAnonymized] = useState(false);
  const [tick, setTick] = useState(0);

  const refetch = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    (async () => {
      try {
        const response = await platformFetch('/api/v1/auth/platform/me');
        if (cancelled) return;
        if (response.status === 200) {
          const body = (await response.json()) as PlatformOperatorMe;
          setData(body);
          setLoading(false);
          setError(null);
          setAnonymized(false);
          return;
        }
        if (response.status === 410) {
          // J2: operator row anonymized after token issued. The SPA must
          // surface this as a distinct UX (no "session expired" / no
          // login retry) — looping login would also fail since the
          // backend filters anonymized rows.
          setData(null);
          setLoading(false);
          setError(null);
          setAnonymized(true);
          return;
        }
        // 401/403 trigger redirects inside platformFetch; this branch
        // is only reachable for unexpected 4xx/5xx.
        setData(null);
        setLoading(false);
        setError(null);
        setAnonymized(false);
      } catch (err) {
        if (cancelled) return;
        setData(null);
        setLoading(false);
        setError(err instanceof Error ? err : new Error(String(err)));
        setAnonymized(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [tick]);

  return { data, loading, error, anonymized, refetch };
}
