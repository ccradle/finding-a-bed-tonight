import { useMemo } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Parse deep-link query parameters embedded inside the location hash
 * (e.g. {@code #dvEscalations?referralId=abc-123} → {@code referralId=abc-123}).
 *
 * <p>The admin panel uses the hash for tab selection ({@code #dvEscalations}
 * selects the DV Escalations tab — D1 rationale), which means standard
 * {@code useSearchParams()} won't surface deep-link identifiers: those live
 * AFTER the hash, not in {@code location.search}. This hook bridges the gap
 * by extracting the substring after the first {@code ?} in the hash and
 * wrapping it in a {@link URLSearchParams}, which is exactly the shape the
 * {@link useDeepLink} hook consumes.</p>
 *
 * <p>Non-admin routes (coordinator dashboard, my-past-holds) use standard
 * {@code useSearchParams()}. Only hosts reached through {@code /admin#tab}
 * need this hash-aware variant.</p>
 *
 * <p>Returns a stable {@link URLSearchParams} reference while the hash is
 * unchanged — {@code useDeepLink}'s URL-effect compares by serialized
 * intent, but keeping the identity stable is cheaper on the hook's
 * {@code JSON.stringify} shortcut and avoids unrelated re-renders in
 * consumers that might memoize on the params reference.</p>
 */
export function useHashSearchParams(): URLSearchParams {
  const location = useLocation();
  return useMemo(() => {
    const queryPart = location.hash.split('?')[1] ?? '';
    return new URLSearchParams(queryPart);
  }, [location.hash]);
}
