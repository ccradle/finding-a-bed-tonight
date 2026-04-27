/**
 * Persistent "PLATFORM OPERATOR MODE" banner across all `/platform/*`
 * routes (F11 task 4.10 / spec Requirement: Persistent platform-operator
 * banner).
 *
 * Renders:
 *   - operator email (masked per operator decision 9.2 — `c***@gmail.com`)
 *   - 15-min session-expiry countdown ("Session expires in 14:23")
 *     - amber at <=2 min
 *     - red at <=30 s
 *     - on tick-zero: clear sessionStorage + redirect to /platform/login
 *       with a "Session expired" toast (the toast is set via
 *       sessionStorage one-shot key picked up on the login page)
 *   - Logout button
 *     - POSTs /api/v1/auth/platform/logout (best-effort — wipes
 *       sessionStorage regardless of response per spec)
 *     - clears sessionStorage + redirects to /platform/login
 */

import { useCallback, useEffect, useState } from 'react';
import { color } from '../../../theme/colors';
import { usePlatformAuth } from '../../../auth/PlatformAuthContext';
import { secondsUntilExpiry } from '../helpers/platformJwt';
import { platformFetch } from '../helpers/platformApi';
import { useOperatorMetadata } from '../helpers/useOperatorMetadata';
import { maskEmail } from '../helpers/maskEmail';

const SESSION_EXPIRED_TOAST_KEY = 'fabt.platform.toast.session-expired';

function formatCountdown(seconds: number): string {
  const safe = Math.max(0, seconds);
  const m = Math.floor(safe / 60);
  const s = safe % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

function countdownColor(seconds: number): string {
  if (seconds <= 30) return '#fef2f2'; // near-white background; we use bright red text
  if (seconds <= 120) return '#fef9c3'; // amber
  return color.textInverse;
}

export function PlatformOperatorBanner() {
  const { jwt, logout } = usePlatformAuth();
  const { data: operator } = useOperatorMetadata();
  const [secondsLeft, setSecondsLeft] = useState<number>(() => secondsUntilExpiry(jwt));

  // Tick the countdown every 1s. Cheap (single setInterval per banner
  // instance) and the spec requires per-second update. The session
  // expiry redirect happens HERE, not in the route guard, because the
  // guard only re-renders on parent changes — the countdown is the
  // load-bearing trigger.
  useEffect(() => {
    const tick = () => {
      const remaining = secondsUntilExpiry(jwt);
      setSecondsLeft(remaining);
      if (remaining <= 0) {
        sessionStorage.setItem(SESSION_EXPIRED_TOAST_KEY, 'true');
        logout();
        window.location.href = '/platform/login';
      }
    };
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [jwt, logout]);

  const handleLogout = useCallback(async () => {
    // Best-effort backend POST. Wipe sessionStorage regardless per
    // spec — a network-failed logout still ends the local session.
    try {
      await platformFetch('/api/v1/auth/platform/logout', { method: 'POST' });
    } catch {
      // Swallow — the local-side logout below is the contract.
    }
    logout();
    window.location.href = '/platform/login';
  }, [logout]);

  return (
    <header
      style={{
        backgroundColor: color.platform,
        color: color.textInverse,
        padding: '0.5rem 1rem',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: '1rem',
        fontSize: '0.875rem',
        fontWeight: 600,
      }}
      role="banner"
      data-testid="platform-operator-banner"
    >
      <span style={{ letterSpacing: '0.05em' }}>
        PLATFORM OPERATOR MODE
        {operator?.email && (
          <span style={{ marginLeft: '0.75rem', fontWeight: 400 }}>
            — <span data-testid="platform-banner-email">{maskEmail(operator.email)}</span>
          </span>
        )}
      </span>
      <span style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
        <span
          aria-live="polite"
          style={{ color: countdownColor(secondsLeft), fontVariantNumeric: 'tabular-nums' }}
          data-testid="platform-banner-countdown"
        >
          Session expires in {formatCountdown(secondsLeft)}
        </span>
        <button
          type="button"
          onClick={handleLogout}
          data-testid="platform-banner-logout"
          style={{
            backgroundColor: 'transparent',
            color: color.textInverse,
            border: `1px solid ${color.textInverse}`,
            borderRadius: '4px',
            padding: '0.25rem 0.75rem',
            cursor: 'pointer',
            fontWeight: 600,
          }}
        >
          Logout
        </button>
      </span>
    </header>
  );
}
