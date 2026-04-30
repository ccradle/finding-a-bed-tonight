import { useState, useEffect } from 'react';
import { api } from '../services/api';

interface ActiveCountiesResponse {
  activeCounties: string[];
}

/**
 * Reads the resolved active_counties list for the caller's tenant from
 * `GET /api/v1/active-counties` (transitional-reentry-support slice 4
 * prereq, warroom H1). Used by:
 *
 *   - `OutreachSearch.tsx` to populate the §9.3 county filter dropdown
 *     (must work for OUTREACH_WORKER — that's the whole reason the
 *     dedicated endpoint exists; the broader `/tenants/{id}/config` is
 *     COC_ADMIN-only).
 *   - `ShelterForm.tsx` for the §9.1 admin county dropdown.
 *
 * <p>Loading semantics (slice 4 §8/§9 warroom S2): the page renders
 * immediately; the dropdown is disabled with a "Loading…" placeholder
 * while the fetch is in flight. On error or offline, the consumer
 * decides — typically degrade the dropdown to a free-text input.
 *
 * <p>Empty-list semantics matter: per design D3, an explicit
 * `active_counties = []` config means "validation disabled, free text
 * accepted" — UI should NOT render an empty dropdown. Consumers
 * differentiate `counties.length === 0 && !loading` from `loading`
 * before rendering. The hook does not impose that policy; it just
 * surfaces the data.
 */
export function useActiveCounties(): {
  counties: string[];
  loading: boolean;
  error: string | null;
} {
  const [counties, setCounties] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .get<ActiveCountiesResponse>('/api/v1/active-counties')
      .then((data) => {
        if (cancelled) return;
        setCounties(data?.activeCounties || []);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const apiErr = err as { message?: string };
        setError(apiErr.message || 'Failed to load counties');
      })
      .finally(() => {
        if (cancelled) return;
        setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return { counties, loading, error };
}
