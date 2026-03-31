import { useState, useEffect, useCallback, useRef } from 'react';
import { fetchEventSource } from '@microsoft/fetch-event-source';
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

const MAX_NOTIFICATIONS = 50;

/** Custom events dispatched on window for page-level refresh triggers */
export const SSE_REFERRAL_UPDATE = 'fabt:referral-update';
export const SSE_AVAILABILITY_UPDATE = 'fabt:availability-update';

/**
 * SSE notification stream using @microsoft/fetch-event-source.
 *
 * Improvements over native EventSource:
 * - Auth via Authorization header (eliminates query-param token leak)
 * - Exponential backoff with jitter on reconnect (prevents thundering herd)
 * - Page Visibility: auto-close when tab hidden, reconnect on visible
 * - No catchUp refetch storm — server replays via Last-Event-ID buffer
 * - Only 'refresh' event triggers bulk refetch (when gap too large)
 */
export function useNotifications(): UseNotificationsReturn {
  const { token, isAuthenticated } = useAuth();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [connected, setConnected] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const retryCountRef = useRef(0);

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

  useEffect(() => {
    if (!isAuthenticated || !token) return;

    const abortController = new AbortController();
    abortRef.current = abortController;

    const baseUrl = import.meta.env.VITE_API_URL || '';
    const url = `${baseUrl}/api/v1/notifications/stream`;

    fetchEventSource(url, {
      headers: {
        'Authorization': `Bearer ${token}`,
      },
      signal: abortController.signal,
      openWhenHidden: false, // Auto-close when tab backgrounded, reconnect on visible

      async onopen(response) {
        if (response.ok) {
          setConnected(true);
          retryCountRef.current = 0;
        } else {
          throw new Error(`SSE connection failed: ${response.status}`);
        }
      },

      onmessage(ev) {
        retryCountRef.current = 0; // Reset retry on any message

        // Dispatch based on event type
        switch (ev.event) {
          case 'connected':
            // Initial connection confirmation — no UI action needed
            break;

          case 'heartbeat':
            // Keepalive — no UI action, but advances Last-Event-ID
            break;

          case 'refresh':
            // Server says gap too large — do single bulk refetch
            window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
            window.dispatchEvent(new Event(SSE_AVAILABILITY_UPDATE));
            break;

          case 'dv-referral.responded':
          case 'dv-referral.requested':
            try {
              const referralData = JSON.parse(ev.data);
              addNotification(ev.event, referralData, ev.id);
              window.dispatchEvent(new Event(SSE_REFERRAL_UPDATE));
            } catch { /* malformed event */ }
            break;

          case 'availability.updated':
            try {
              const availData = JSON.parse(ev.data);
              addNotification(ev.event, availData, ev.id);
              window.dispatchEvent(new Event(SSE_AVAILABILITY_UPDATE));
            } catch { /* malformed event */ }
            break;

          default:
            // Unknown event type — ignore
            break;
        }
      },

      onerror(err) {
        setConnected(false);

        // Exponential backoff with jitter: 1s, 2s, 4s, 8s, 16s, 30s max
        retryCountRef.current++;
        const baseDelay = Math.min(1000 * Math.pow(2, retryCountRef.current - 1), 30000);
        const jitter = baseDelay * 0.3 * Math.random();
        const delay = baseDelay + jitter;

        // Return the delay in ms — fetch-event-source will retry after this
        // Returning nothing would retry immediately; throwing would stop retrying
        return delay;
      },

      onclose() {
        setConnected(false);
      },
    });

    return () => {
      abortController.abort();
      abortRef.current = null;
      setConnected(false);
    };
  }, [isAuthenticated, token, addNotification]);

  return {
    notifications,
    unreadCount,
    markRead,
    markAllRead,
    dismiss,
    connected,
  };
}
