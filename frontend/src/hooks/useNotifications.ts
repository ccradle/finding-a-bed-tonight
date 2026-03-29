import { useState, useEffect, useCallback, useRef } from 'react';
import { useAuth } from '../auth/useAuth';

export interface Notification {
  id: string;
  eventType: string;
  data: Record<string, unknown>;
  timestamp: number;
  read: boolean;
}

interface UseNotificationsReturn {
  notifications: Notification[];
  unreadCount: number;
  markRead: (id: string) => void;
  markAllRead: () => void;
  dismiss: (id: string) => void;
  connected: boolean;
}

const MAX_NOTIFICATIONS = 10;

/** Custom events dispatched on window for page-level refresh triggers */
export const SSE_REFERRAL_UPDATE = 'fabt:referral-update';
export const SSE_AVAILABILITY_UPDATE = 'fabt:availability-update';

/**
 * Establishes EventSource to SSE endpoint and manages notification state.
 * Dispatches window custom events so pages can auto-refresh.
 * Reconnection: on error/reconnect, dispatches refresh events to catch up via REST.
 */
export function useNotifications(): UseNotificationsReturn {
  const { token, isAuthenticated } = useAuth();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [connected, setConnected] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const reconnectingRef = useRef(false);

  const unreadCount = notifications.filter((n) => !n.read).length;

  const markRead = useCallback((id: string) => {
    setNotifications((prev) => prev.map((n) => (n.id === id ? { ...n, read: true } : n)));
  }, []);

  const markAllRead = useCallback(() => {
    setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
  }, []);

  const dismiss = useCallback((id: string) => {
    setNotifications((prev) => prev.filter((n) => n.id !== id));
  }, []);

  const addNotification = useCallback((eventType: string, data: Record<string, unknown>, eventId: string) => {
    setNotifications((prev) => {
      const notification: Notification = {
        id: eventId || String(Date.now()),
        eventType,
        data,
        timestamp: Date.now(),
        read: false,
      };
      return [notification, ...prev].slice(0, MAX_NOTIFICATIONS);
    });
  }, []);

  // REST catch-up on reconnection
  const catchUp = useCallback(() => {
    if (reconnectingRef.current) return;
    reconnectingRef.current = true;
    try {
      window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
      window.dispatchEvent(new Event(SSE_AVAILABILITY_UPDATE));
    } finally {
      reconnectingRef.current = false;
    }
  }, []);

  useEffect(() => {
    if (!isAuthenticated || !token) return;

    const baseUrl = import.meta.env.VITE_API_URL || '';
    const url = `${baseUrl}/api/v1/notifications/stream?token=${encodeURIComponent(token)}`;
    const es = new EventSource(url);
    eventSourceRef.current = es;

    es.onopen = () => {
      setConnected(true);
    };

    es.addEventListener('dv-referral.responded', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        addNotification('dv-referral.responded', data, event.lastEventId);
        window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
      } catch { /* malformed event */ }
    });

    es.addEventListener('dv-referral.requested', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        addNotification('dv-referral.requested', data, event.lastEventId);
        window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
      } catch { /* malformed event */ }
    });

    es.addEventListener('availability.updated', (event: MessageEvent) => {
      try {
        const data = JSON.parse(event.data);
        addNotification('availability.updated', data, event.lastEventId);
        window.dispatchEvent(new Event(SSE_AVAILABILITY_UPDATE));
      } catch { /* malformed event */ }
    });

    es.onerror = () => {
      setConnected(false);
      // EventSource auto-reconnects. On reconnect, catch up via REST.
      catchUp();
    };

    return () => {
      es.close();
      eventSourceRef.current = null;
      setConnected(false);
    };
  }, [isAuthenticated, token, addNotification, catchUp]);

  return {
    notifications,
    unreadCount,
    markRead,
    markAllRead,
    dismiss,
    connected,
  };
}
