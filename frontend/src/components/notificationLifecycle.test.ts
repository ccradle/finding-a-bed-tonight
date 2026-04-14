import { describe, it, expect } from 'vitest';
import {
  deriveLifecycleState,
  stateLabelIdFor,
  stateTooltipIdFor,
  stateInlineLabelIdFor,
  type NotificationLifecycleState,
} from './notificationLifecycle';
import type { Notification } from '../hooks/useNotifications';

function build(overrides: Partial<Notification>): Notification {
  return {
    id: 'n1',
    eventType: 'test',
    data: {},
    timestamp: 0,
    read: false,
    acted: false,
    ...overrides,
  };
}

/**
 * Pins the 3-state lifecycle contract used by NotificationBell's three
 * visual treatments. Extracted per war-room M-1 from Phase 3 session 4 —
 * the inline decision in the bell render touched 3 places (body style,
 * label key, tooltip key) and a typo in any one branch would ship without
 * a test catching it.
 */

describe('deriveLifecycleState', () => {
  it('returns unread when read=false and acted=false', () => {
    expect(deriveLifecycleState(build({}))).toBe('unread');
  });

  it('returns pending when read=true and acted=false', () => {
    expect(deriveLifecycleState(build({ read: true }))).toBe('pending');
  });

  it('returns acted when acted=true, regardless of read', () => {
    // A user who acted on the notification has by definition read it too.
    // The acted state wins.
    expect(deriveLifecycleState(build({ read: true, acted: true }))).toBe('acted');
    // Defensive: if acted=true and read=false (shouldn't happen in practice
    // but is a reachable state if the backend sets actedAt without readAt),
    // still treat as acted.
    expect(deriveLifecycleState(build({ read: false, acted: true }))).toBe('acted');
  });
});

describe('stateLabelIdFor — aria-label state word', () => {
  it.each<[NotificationLifecycleState, string]>([
    ['unread', 'notifications.state.unread'],
    ['pending', 'notifications.state.pending'],
    ['acted', 'notifications.state.acted'],
  ])('%s → %s', (state, expected) => {
    expect(stateLabelIdFor(state)).toBe(expected);
  });
});

describe('stateTooltipIdFor — native-tooltip title text', () => {
  it.each<[NotificationLifecycleState, string]>([
    ['unread', 'notifications.state.unreadTooltip'],
    ['pending', 'notifications.state.pendingTooltip'],
    ['acted', 'notifications.state.actedTooltip'],
  ])('%s → %s', (state, expected) => {
    expect(stateTooltipIdFor(state)).toBe(expected);
  });
});

describe('stateInlineLabelIdFor — indicator text next to ✓ or •', () => {
  it('returns null for unread (no inline indicator — the bg highlight + badge suffice)', () => {
    expect(stateInlineLabelIdFor('unread')).toBeNull();
  });

  it('returns Pending i18n key for pending', () => {
    expect(stateInlineLabelIdFor('pending')).toBe('notifications.state.pendingLabel');
  });

  it('returns Completed i18n key for acted', () => {
    expect(stateInlineLabelIdFor('acted')).toBe('notifications.state.actedLabel');
  });
});
