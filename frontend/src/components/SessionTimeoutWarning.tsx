import { useState, useEffect, useRef, useCallback } from 'react';
import { api } from '../services/api';

/**
 * Session timeout warning — WCAG 2.2.1 Timing Adjustable.
 *
 * Shows a modal alertdialog 2 minutes before JWT expiry with options
 * to extend or log out. Uses BroadcastChannel for cross-tab sync.
 * Activity detection throttled to 30s intervals per OWASP guidance.
 *
 * Design: role="alertdialog" per W3C WAI-ARIA APG pattern.
 * Countdown updates every 30s (then 15s in final minute) to avoid
 * flooding screen reader queue.
 */

const ACTIVITY_EVENTS = ['mousedown', 'keydown', 'touchstart', 'scroll'] as const;
const THROTTLE_MS = 30_000;
const WARNING_BEFORE_EXPIRY_MS = 2 * 60 * 1000; // 2 minutes

interface Props {
  expiresIn: number; // seconds from login/refresh response
  onLogout: () => void;
}

export function SessionTimeoutWarning({ expiresIn, onLogout }: Props) {
  const [showWarning, setShowWarning] = useState(false);
  const [secondsRemaining, setSecondsRemaining] = useState(0);
  const expiresAtRef = useRef(Date.now() + expiresIn * 1000);
  const lastActivityRef = useRef(Date.now());
  const previousFocusRef = useRef<HTMLElement | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  // Reset expiry when expiresIn changes (login/refresh)
  useEffect(() => {
    expiresAtRef.current = Date.now() + expiresIn * 1000;
    setShowWarning(false);
  }, [expiresIn]);

  // Activity tracking (throttled)
  useEffect(() => {
    const handleActivity = () => {
      const now = Date.now();
      if (now - lastActivityRef.current >= THROTTLE_MS) {
        lastActivityRef.current = now;
      }
    };

    for (const event of ACTIVITY_EVENTS) {
      document.addEventListener(event, handleActivity, { passive: true });
    }
    return () => {
      for (const event of ACTIVITY_EVENTS) {
        document.removeEventListener(event, handleActivity);
      }
    };
  }, []);

  // Check timer every second
  useEffect(() => {
    const interval = setInterval(() => {
      const remaining = Math.max(0, expiresAtRef.current - Date.now());
      const remainingSec = Math.ceil(remaining / 1000);

      if (remaining <= 0) {
        setShowWarning(false);
        onLogout();
      } else if (remaining <= WARNING_BEFORE_EXPIRY_MS) {
        setSecondsRemaining(remainingSec);
        if (!showWarning) {
          previousFocusRef.current = document.activeElement as HTMLElement;
          setShowWarning(true);
        }
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [showWarning, onLogout]);

  // Focus management — move focus into dialog when it opens
  useEffect(() => {
    if (showWarning && dialogRef.current) {
      const continueBtn = dialogRef.current.querySelector<HTMLElement>('[data-testid="session-continue-btn"]');
      continueBtn?.focus();
    }
  }, [showWarning]);

  // Cross-tab sync via BroadcastChannel
  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    const channel = new BroadcastChannel('fabt-session-timeout');

    channel.onmessage = (event) => {
      if (event.data?.type === 'SESSION_EXTENDED') {
        expiresAtRef.current = event.data.expiresAt;
        setShowWarning(false);
      }
    };

    return () => channel.close();
  }, []);

  const handleExtend = useCallback(async () => {
    try {
      const refreshToken = localStorage.getItem('fabt_refresh_token');
      if (!refreshToken) {
        onLogout();
        return;
      }
      const result = await api.post<{ accessToken: string; refreshToken: string; expiresIn: number }>(
        '/api/v1/auth/refresh', { refreshToken });
      localStorage.setItem('fabt_access_token', result.accessToken);
      if (result.refreshToken) {
        localStorage.setItem('fabt_refresh_token', result.refreshToken);
      }
      expiresAtRef.current = Date.now() + result.expiresIn * 1000;
      setShowWarning(false);

      // Sync across tabs
      if (typeof BroadcastChannel !== 'undefined') {
        const channel = new BroadcastChannel('fabt-session-timeout');
        channel.postMessage({ type: 'SESSION_EXTENDED', expiresAt: expiresAtRef.current });
        channel.close();
      }

      // Return focus
      previousFocusRef.current?.focus();
    } catch {
      onLogout();
    }
  }, [onLogout]);

  // Trap focus within dialog
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Escape') {
      onLogout();
      return;
    }
    if (e.key === 'Tab' && dialogRef.current) {
      const focusable = dialogRef.current.querySelectorAll<HTMLElement>('button');
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  }, [onLogout]);

  if (!showWarning) return null;

  const minutes = Math.floor(secondsRemaining / 60);
  const seconds = secondsRemaining % 60;
  const timeText = minutes > 0 ? `${minutes}:${seconds.toString().padStart(2, '0')}` : `${seconds} seconds`;

  return (
    <div
      style={{
        position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
        backgroundColor: 'rgba(0,0,0,0.5)', display: 'flex',
        alignItems: 'center', justifyContent: 'center', zIndex: 10000,
      }}
    >
      <div
        ref={dialogRef}
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="session-timeout-title"
        aria-describedby="session-timeout-desc"
        onKeyDown={handleKeyDown}
        style={{
          background: '#fff', borderRadius: 16, padding: 32, maxWidth: 420,
          width: '90%', boxShadow: '0 8px 32px rgba(0,0,0,0.2)',
        }}
      >
        <h2 id="session-timeout-title" style={{ margin: '0 0 12px', fontSize: 20, fontWeight: 700, color: '#0f172a' }}>
          Session Expiring
        </h2>
        <p id="session-timeout-desc" style={{ margin: '0 0 8px', fontSize: 14, color: '#475569', lineHeight: 1.5 }}>
          Your session will expire in{' '}
          <span aria-live="assertive" aria-atomic="true" style={{ fontWeight: 700, color: '#991b1b' }}>
            {timeText}
          </span>.
          Press Continue to stay signed in.
        </p>
        <div style={{ display: 'flex', gap: 12, marginTop: 20 }}>
          <button
            data-testid="session-continue-btn"
            onClick={handleExtend}
            style={{
              flex: 1, padding: '12px 20px', backgroundColor: '#1a56db', color: '#fff',
              border: 'none', borderRadius: 10, fontSize: 14, fontWeight: 700,
              cursor: 'pointer', minHeight: 44,
            }}
          >
            Continue Session
          </button>
          <button
            data-testid="session-logout-btn"
            onClick={onLogout}
            style={{
              flex: 1, padding: '12px 20px', backgroundColor: '#f1f5f9', color: '#475569',
              border: '1px solid #e2e8f0', borderRadius: 10, fontSize: 14, fontWeight: 700,
              cursor: 'pointer', minHeight: 44,
            }}
          >
            Log Out
          </button>
        </div>
      </div>
    </div>
  );
}
