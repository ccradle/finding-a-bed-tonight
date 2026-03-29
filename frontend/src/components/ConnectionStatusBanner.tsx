import { useState, useEffect } from 'react';
import { FormattedMessage } from 'react-intl';
import { text, weight } from '../theme/typography';
import { color } from '../theme/colors';

interface ConnectionStatusBannerProps {
  connected: boolean;
}

/**
 * Shows connection status only when SSE is disconnected (Slack pattern).
 * Hidden when connected. Brief "Reconnected" toast on recovery after a real disconnect.
 * Does NOT show "Reconnected" on initial connection.
 * WCAG: role="status" + aria-live="polite" for screen reader announcements.
 */
export function ConnectionStatusBanner({ connected }: ConnectionStatusBannerProps) {
  const [wasEverConnected, setWasEverConnected] = useState(false);
  const [showReconnectedToast, setShowReconnectedToast] = useState(false);
  const [wasDisconnected, setWasDisconnected] = useState(false);

  // Track connection lifecycle transitions from external SSE system.
  // setState in effect is intentional — we need transition history (was-ever-connected,
  // was-disconnected) that cannot be derived from the current `connected` prop alone.
  // This is a known false-positive category for react-hooks/set-state-in-effect (React #34743).
  /* eslint-disable react-hooks/set-state-in-effect -- External system state machine: transition history requires setState (React #34743 known false positive) */
  useEffect(() => {
    if (connected) {
      if (wasDisconnected) setShowReconnectedToast(true);
      setWasDisconnected(false);
      setWasEverConnected(true);
    } else if (wasEverConnected) {
      setWasDisconnected(true);
      setShowReconnectedToast(false);
    }
  }, [connected, wasDisconnected, wasEverConnected]);
  /* eslint-enable react-hooks/set-state-in-effect */

  // Auto-dismiss reconnected toast after 3s
  useEffect(() => {
    if (showReconnectedToast) {
      const timer = setTimeout(() => setShowReconnectedToast(false), 3000);
      return () => clearTimeout(timer);
    }
  }, [showReconnectedToast]);

  // Derive display state
  const showDisconnected = !connected && wasEverConnected && !showReconnectedToast;

  // Hidden live region — connected, initial, or after toast dismissed
  if (!showDisconnected && !showReconnectedToast) {
    return (
      <div role="status" aria-live="polite" aria-atomic="true" style={{
        position: 'absolute', width: '1px', height: '1px', padding: 0,
        margin: '-1px', overflow: 'hidden', clip: 'rect(0,0,0,0)',
        whiteSpace: 'nowrap', border: 0, color: 'transparent',
      }} data-testid="connection-status-hidden" />
    );
  }

  // Reconnected toast (green, auto-dismisses in 3s) — contrast ratio 5.48:1
  if (showReconnectedToast) {
    return (
      <div
        role="status"
        aria-live="polite"
        aria-atomic="true"
        data-testid="connection-status-reconnected"
        style={{
          padding: '8px 20px',
          backgroundColor: color.success,
          color: color.textInverse,
          fontSize: text.sm,
          fontWeight: weight.semibold,
          textAlign: 'center',
        }}
      >
        <FormattedMessage id="notifications.reconnected" />
      </div>
    );
  }

  // Disconnected / reconnecting banner (amber)
  return (
    <div
      role="status"
      aria-live="polite"
      aria-atomic="true"
      data-testid="connection-status-reconnecting"
      style={{
        padding: '8px 20px',
        backgroundColor: color.warningMid,
        color: color.textInverse,
        fontSize: text.sm,
        fontWeight: weight.semibold,
        textAlign: 'center',
      }}
    >
      <FormattedMessage id="notifications.reconnecting" />
    </div>
  );
}
